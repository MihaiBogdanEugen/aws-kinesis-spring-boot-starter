package de.bringmeister.spring.aws.kinesis

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.mockito.Answers
import org.springframework.beans.factory.ObjectProvider
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClientBuilder
import software.amazon.kinesis.common.ConfigsBuilder
import software.amazon.kinesis.common.InitialPositionInStream
import software.amazon.kinesis.metrics.LogMetricsFactory
import software.amazon.kinesis.metrics.MetricsLevel
import java.net.URI

class SettingsClientConfigCustomizerFactoryTest {

    private val mockRegistryProvider = mock<ObjectProvider<MeterRegistry>> { }

    private val credentialsProvider = AnonymousCredentialsProvider.create()
    private val stsCredentialsProvider = DefaultCredentialsProvider.create()
    private val credentialsProviderFactory = mock<AwsCredentialsProviderFactory> {
        on { credentials("arn:aws:iam::100000000042:role/kinesis-user-role") } doReturn stsCredentialsProvider
    }
    private val settings = AwsKinesisSettingsTestFactory.settings()
        .withRequired()
        .withConsumerFor("my-kinesis-stream", metricsLevel = MetricsLevel.DETAILED, metricsDriver = StreamSettings.MetricsDriver.LOGGING)
        .build()
    private val customizerFactory = SettingsClientConfigCustomizerFactory(credentialsProvider, credentialsProviderFactory, settings, mockRegistryProvider)
    private val customizer = customizerFactory.customizerFor("my-kinesis-stream")
    private val defaults = ConfigsBuilder(
        "my-kinesis-stream",
        "my-application",
        KinesisAsyncClient.create(),
        DynamoDbAsyncClient.create(),
        CloudWatchAsyncClient.create(),
        "my-worker"
    ) { throw NotImplementedError() }

    @Test
    fun `should generate application name from consumer group and stream name`() {
        assertThat(customizer.applicationName()).isEqualTo("my-consumer-group_my-kinesis-stream")
    }

    @Test
    fun `should generate stable worker identifier, unique per instance`() {
        assertThat(customizer.workerIdentifier())
            .isNotBlank()
            .isEqualTo(customizer.workerIdentifier())

        assertThat(customizer.workerIdentifier())
            .isNotEqualTo(customizerFactory.customizerFor("my-kinesis-stream").workerIdentifier())
    }

    @Test
    fun `should set initial position in stream`() {

        val config = customizer.customize(defaults.retrievalConfig())
        assertThat(config.initialPositionInStreamExtended().initialPositionInStream)
            .isEqualTo(InitialPositionInStream.LATEST)
    }

    @Test
    fun `should set metrics level`() {

        val config = customizer.customize(defaults.metricsConfig())
        assertThat(config.metricsLevel()).isEqualTo(MetricsLevel.DETAILED)
    }

    @Test
    fun `should set metrics driver logging`() {

        val config = customizer.customize(defaults.metricsConfig())
        assertThat(config.metricsFactory()).isInstanceOf(LogMetricsFactory::class.java)
    }

    @Test
    fun `should set lease capacities`() {

        val config = customizer.customize(defaults.leaseManagementConfig())
        assertThat(config.initialLeaseTableReadCapacity()).isEqualTo(3)
        assertThat(config.initialLeaseTableWriteCapacity()).isEqualTo(5)
    }

    @Test
    fun `should configure Kinesis client`() {

        val mockBuilder = mock<KinesisAsyncClientBuilder>(defaultAnswer = Answers.RETURNS_SELF)
        val builder = customizer.customize(mockBuilder)
        assertThat(builder).isSameAs(mockBuilder)

        verify(mockBuilder).region(Region.of("local"))
        verify(mockBuilder).endpointOverride(URI.create("https://kinesis.eu-central-1.amazonaws.com"))
        verify(mockBuilder).credentialsProvider(stsCredentialsProvider)
    }

    @Test
    fun `should configure DynamoDB client`() {

        val mockBuilder = mock<DynamoDbAsyncClientBuilder>(defaultAnswer = Answers.RETURNS_SELF)
        val builder = customizer.customize(mockBuilder)
        assertThat(builder).isSameAs(mockBuilder)

        verify(mockBuilder).region(Region.of("local"))
        verify(mockBuilder).endpointOverride(URI.create("https://dynamo-endpoint-url.com"))
        verify(mockBuilder).credentialsProvider(credentialsProvider)
    }

    @Test
    fun `should configure CloudWatch client`() {

        val mockBuilder = mock<CloudWatchAsyncClientBuilder>(defaultAnswer = Answers.RETURNS_SELF)
        val builder = customizer.customize(mockBuilder)
        assertThat(builder).isSameAs(mockBuilder)

        verify(mockBuilder).region(Region.of("local"))
        verify(mockBuilder).credentialsProvider(credentialsProvider)
    }
}
