package de.bringmeister.spring.aws.kinesis;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import de.bringmeister.connect.erpproductfacade.ports.event.KotlinListenerTest;
import de.bringmeister.spring.aws.kinesis.local.KinesisLocalConfiguration;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.testcontainers.containers.GenericContainer;

import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

@ActiveProfiles({ "kinesis-local", "consumer", "producer" })
@SpringBootTest(classes = {
        JavaTestListener.class,
        JacksonConfiguration.class,
        JacksonAutoConfiguration.class,
        AwsKinesisAutoConfiguration.class,
        KinesisLocalConfiguration.class,
        KotlinListenerTest.DummyAWSCredentialsConfiguration.class
})
@RunWith(SpringRunner.class)
public class JavaListenerTest {

    @Autowired
    private AwsKinesisOutboundGateway outbound;

    public static CountDownLatch LATCH = new CountDownLatch(1);

    @ClassRule
    public static GenericContainer KINESIS_CONTAINER = new GenericContainer("instructure/kinesalite:latest").withCreateContainerCmdModifier(new Consumer<CreateContainerCmd>() {
        @Override
        public void accept(CreateContainerCmd createContainerCmd) {
            createContainerCmd.withPortBindings(new Ports(new PortBinding(new Ports.Binding("localhost", "14567"), ExposedPort.tcp(4567))));
        }
    });

    @ClassRule
    public static GenericContainer DYNAMODB_CONTAINER = new GenericContainer("richnorth/dynalite:latest").withCreateContainerCmdModifier(new Consumer<CreateContainerCmd>() {
        @Override
        public void accept(CreateContainerCmd createContainerCmd) {
            createContainerCmd.withPortBindings(new Ports(new PortBinding(new Ports.Binding("localhost", "14568"), ExposedPort.tcp(4567))));
        }
    });

    @Test
    public void should_send_and_receive_events() throws InterruptedException {

        FooCreatedEvent fooEvent = new FooCreatedEvent("any-field");
        EventMetadata metadata = new EventMetadata("test");

        outbound.send("foo-event-stream", fooEvent, metadata);

        LATCH.await(); // wait for event-listener thread to process event

        // If we come to this point, the LATCH was counted down!
        // This means the event has been consumed - test succeeded!
    }

    @Configuration
    private class DummyAWSCredentialsConfiguration {

        @Bean
        @Primary
        public AWSStaticCredentialsProvider credentialsProvider() {
            return new AWSStaticCredentialsProvider(new AWSCredentials() {

                @Override
                public String getAWSAccessKeyId() {
                    return "no-key";
                }

                @Override
                public String getAWSSecretKey() {
                    return "no-passwd";
                }
            });
        }
    }
}