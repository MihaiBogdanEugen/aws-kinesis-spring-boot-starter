package de.bringmeister.spring.aws.kinesis

import com.amazonaws.services.kinesis.metrics.interfaces.MetricsLevel
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test
import org.springframework.beans.factory.annotation.Autowired

class AwsKinesisDefaultSettingsTest : AbstractTest() {

    @Autowired
    lateinit var settings: AwsKinesisSettings

    @Test
    fun `should use default kinesis settings`() {
        assertThat(settings.kinesisUrl, equalTo("http://localhost:14567"))
        assertThat(settings.region, equalTo("local"))
    }

    @Test
    fun `should use default consumer settings`() {
        assertThat(settings.consumer[0].dynamoDBSettings.url, equalTo("http://localhost:14568"))
        assertThat(settings.consumer[0].metricsLevel, equalTo(MetricsLevel.NONE.name))
        assertThat(settings.consumer[0].dynamoDBSettings.leaseTableReadCapacity, equalTo(1))
        assertThat(settings.consumer[0].dynamoDBSettings.leaseTableWriteCapacity, equalTo(1))
    }
}