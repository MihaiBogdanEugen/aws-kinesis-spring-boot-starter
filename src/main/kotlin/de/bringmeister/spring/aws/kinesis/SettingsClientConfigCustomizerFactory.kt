package de.bringmeister.spring.aws.kinesis

import de.bringmeister.spring.aws.kinesis.metrics.MicrometerMetricsFactory
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.SdkSystemSetting
import software.amazon.awssdk.http.Protocol
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import software.amazon.kinesis.common.InitialPositionInStreamExtended
import software.amazon.kinesis.common.KinesisClientUtil
import software.amazon.kinesis.coordinator.CoordinatorConfig
import software.amazon.kinesis.coordinator.CoordinatorFactory
import software.amazon.kinesis.leases.LeaseManagementConfig
import software.amazon.kinesis.metrics.LogMetricsFactory
import software.amazon.kinesis.metrics.MetricsConfig
import software.amazon.kinesis.metrics.NullMetricsFactory
import software.amazon.kinesis.retrieval.RetrievalConfig
import software.amazon.kinesis.retrieval.fanout.FanOutConfig
import software.amazon.kinesis.retrieval.polling.PollingConfig
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService

class SettingsClientConfigCustomizerFactory(
    private val credentialsProvider: AwsCredentialsProvider,
    private val awsCredentialsProviderFactory: AwsCredentialsProviderFactory,
    private val kinesisSettings: AwsKinesisSettings,
    private val registryProvider: ObjectProvider<MeterRegistry>
) : ClientConfigCustomizerFactory {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        if (kinesisSettings.disableCbor) {
            // Kinesis client is failing with <Illegal length for VALUE_STRING: 2473435388096836386>
            // @see https://github.com/aws/aws-sdk-java-v2/issues/1595
            // Apparently, the issue also existed in previous KCL 1.x line:
            // @see https://github.com/aws/aws-sdk-java/issues/1106

            val prop = System.getProperty(SdkSystemSetting.CBOR_ENABLED.property())
            if (prop?.toLowerCase(Locale.ROOT) != "false") {
                log.warn("AWS CBOR is explicitly set via system property to <{}>, but explicitly disabled via application configuration.", prop)
            }

            log.info("Disabling AWS CBOR support. Set `aws.kinesis.disable-cbor: false` in order to use the AWS SDK default.")
            System.setProperty(SdkSystemSetting.CBOR_ENABLED.property(), "false")
        }
    }

    override fun customizerFor(streamName: String): ClientConfigCustomizer = SettingsClientConfigCustomizer(streamName)

    private inner class SettingsClientConfigCustomizer(
        private val streamName: String
    ) : ClientConfigCustomizer {

        private val streamSettings = kinesisSettings.getStreamSettingsOrDefault(streamName)
        private val workerIdentifier = "${InetAddress.getLocalHost().canonicalHostName}:${UUID.randomUUID()}"

        private val tags = Tags.of(
            "streamName", streamName,
            "consumerGroup", kinesisSettings.consumerGroup,
            "applicationName", applicationName(),
            "workerIdentifier", workerIdentifier()
        )

        override fun applicationName(): String = "${kinesisSettings.consumerGroup}_$streamName"
        override fun workerIdentifier(): String = workerIdentifier

        override fun customize(config: RetrievalConfig): RetrievalConfig =
            config
                .initialPositionInStreamExtended(
                    InitialPositionInStreamExtended.newInitialPosition(streamSettings.initialPositionInStream)
                )
                .apply {
                    when (streamSettings.retrievalStrategy) {
                        StreamSettings.RetrievalStrategy.FANOUT -> {
                            log.info("Using strategy <Enhanced Fan-Out> on stream <{}>.", config.streamName())
                            retrievalSpecificConfig(FanOutConfig(config.kinesisClient()))
                        }
                        StreamSettings.RetrievalStrategy.POLLING -> {
                            log.info("Using strategy <Polling> on stream <{}>.", config.streamName())
                            retrievalSpecificConfig(PollingConfig(config.streamName(), config.kinesisClient()))
                        }
                    }
                }

        override fun customize(config: LeaseManagementConfig): LeaseManagementConfig =
            config
                .initialLeaseTableReadCapacity(kinesisSettings.dynamoDbSettings!!.leaseTableReadCapacity)
                .initialLeaseTableWriteCapacity(kinesisSettings.dynamoDbSettings!!.leaseTableWriteCapacity)
                .apply {
                    registryProvider.ifAvailable {
                        executorService(ExecutorServiceMetrics.monitor(
                            it, executorService(), "kinesis-LeaseManagement", tags
                        ))
                    }
                }

        override fun customize(config: MetricsConfig): MetricsConfig =
            config
                .metricsLevel(streamSettings.metricsLevel)
                .apply {
                    val metricsFactory = when (streamSettings.metricsDriver) {
                        StreamSettings.MetricsDriver.DEFAULT -> metricsFactory()
                        StreamSettings.MetricsDriver.NONE -> NullMetricsFactory()
                        StreamSettings.MetricsDriver.LOGGING -> LogMetricsFactory()
                        StreamSettings.MetricsDriver.MICROMETER ->
                            when (val registry = registryProvider.ifUnique) {
                                is MeterRegistry -> MicrometerMetricsFactory(streamName, registry)
                                else -> {
                                    log.warn("No MeterRegistry is available from application context. Metrics will not be exported for <{}>.", streamName)
                                    NullMetricsFactory()
                                }
                            }
                    }
                    metricsFactory(metricsFactory)
                }

        override fun customize(config: CoordinatorConfig): CoordinatorConfig =
            config
                .apply {
                    registryProvider.ifAvailable {
                        val delegate = coordinatorFactory()
                        coordinatorFactory(object : CoordinatorFactory by delegate {
                            override fun createExecutorService(): ExecutorService =
                                ExecutorServiceMetrics.monitor(it, delegate.createExecutorService(), "kinesis-Coordinator", tags)
                        })
                    }
                }

        override fun customize(builder: KinesisAsyncClientBuilder): KinesisAsyncClientBuilder {
            val roleToAssume = streamSettings.roleArn()
            val credentialsProvider = awsCredentialsProviderFactory.credentials(roleToAssume)
            return builder
                .applyMutation {
                    when (streamSettings.retrievalStrategy) {
                        StreamSettings.RetrievalStrategy.FANOUT -> {
                            log.trace("Kinesis client for stream <{}> uses KCL defaults.", streamSettings.streamName)
                            KinesisClientUtil.adjustKinesisClientBuilder(it)
                        }
                        StreamSettings.RetrievalStrategy.POLLING -> {
                            log.debug("Kinesis client for stream <{}> uses http/1.1.", streamSettings.streamName)
                            it.httpClientBuilder(NettyNioAsyncHttpClient.builder().protocol(Protocol.HTTP1_1))
                        }
                    }
                }
                .credentialsProvider(credentialsProvider)
                .region(Region.of(kinesisSettings.region))
                .endpointOverride(URI(kinesisSettings.kinesisUrl!!))
        }

        override fun customize(builder: DynamoDbAsyncClientBuilder): DynamoDbAsyncClientBuilder {
            return builder
                .credentialsProvider(credentialsProvider)
                .region(Region.of(kinesisSettings.region))
                .endpointOverride(URI(kinesisSettings.dynamoDbSettings!!.url))
        }

        override fun customize(builder: CloudWatchAsyncClientBuilder): CloudWatchAsyncClientBuilder {
            return builder
                .credentialsProvider(credentialsProvider)
                .region(Region.of(kinesisSettings.region))
        }
    }
}
