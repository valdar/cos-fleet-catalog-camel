package org.bf2.cos.connector.camel.it

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.bf2.cos.connector.camel.it.aws.AWSContainer
import org.bf2.cos.connector.camel.it.support.KafkaConnectorSpec
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.kinesis.model.Record
import software.amazon.awssdk.utils.IoUtils

import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

@Slf4j
class ConnectorIT extends KafkaConnectorSpec {
    static AWSContainer aws

    @Override
    def setupSpec() {
        aws = new AWSContainer(network, 's3', 'sns', 'sqs', 'kinesis')
        aws.start()
    }

    @Override
    def cleanupSpec() {
        closeQuietly(aws)
    }

    // ********************************************
    //
    // S3
    //
    // ********************************************

    def "s3 sink"() {
        setup:
            def payload = '''{ "username":"oscerd", "city":"Rome" }'''
            def topic = UUID.randomUUID().toString()
            def fileName = 'filetest.txt'
            def group = UUID.randomUUID().toString()

            def cnt = connectorContainer(ConnectorSupport.CONTAINER_IMAGE_S3, """
                - route:
                    from:
                      uri: kamelet:kafka-not-secured-source
                      parameters:
                        topic: ${topic}
                        bootstrapServers: ${kafka.outsideBootstrapServers}
                        groupId: ${group}
                        autoOffsetReset: "earliest"
                    steps:
                    - removeHeader:
                        name: "kafka.HEADERS"
                    - to:
                        uri: "log:s?multiLine=true&showHeaders=true"
                    - to:
                        uri: kamelet:aws-s3-sink
                        parameters:
                          accessKey: ${aws.credentials.accessKeyId()}
                          secretKey: ${aws.credentials.secretAccessKey()}
                          region: ${aws.region}
                          bucketNameOrArn: ${topic}
                          autoCreateBucket: true
                          uriEndpointOverride: ${aws.endpoint}
                          overrideEndpoint: true
                          keyName: ${fileName}
                """)

            cnt.start()

            def s3 = aws.s3()
        when:
            kafka.send(topic, payload, [ 'file': fileName])
        then:
            await(10, TimeUnit.SECONDS) {
                try {
                    return payload == IoUtils.toUtf8String(s3.getObject(b -> b.bucket(topic).key(fileName)))
                } catch (Exception e) {
                    return false
                }
            }
        cleanup:
            closeQuietly(cnt)
    }

    def "s3 source"() {
        setup:
            def payload = '''{ "username":"oscerd", "city":"Rome" }'''
            def topic = UUID.randomUUID().toString()
            def fileName = 'filetest.txt'

            def cnt = connectorContainer(ConnectorSupport.CONTAINER_IMAGE_S3,"""
                - route:
                    from:
                      uri: kamelet:aws-s3-source
                      parameters:
                        accessKey: ${aws.credentials.accessKeyId()}
                        secretKey: ${aws.credentials.secretAccessKey()}
                        region: ${aws.region}
                        bucketNameOrArn: ${topic}
                        autoCreateBucket: true
                        uriEndpointOverride: ${aws.endpoint}
                        overrideEndpoint: true
                    steps:
                      - to:
                          uri: "log:s?multiLine=true&showHeaders=true"
                      - to:
                          uri: kamelet:kafka-not-secured-sink
                          parameters:
                            topic: ${topic}
                            bootstrapServers: ${kafka.outsideBootstrapServers}
                """)

            cnt.start()
        when:
            aws.s3().putObject(
                b -> b.key(fileName).bucket(topic).build(),
                RequestBody.fromString(payload))
        then:
            await(10, TimeUnit.SECONDS) {
                def record = kafka.poll(cnt.containerId, topic).find {
                    it.value() == payload
                }

                return record != null
            }
        cleanup:
            closeQuietly(cnt)
    }

    // ********************************************
    //
    // SNS
    //
    // ********************************************

    def "sns sink"() {
        setup:
            def payload = '''{ "username":"oscerd", "city":"Rome" }'''
            def topic = UUID.randomUUID().toString()
            def group = UUID.randomUUID().toString()
            def sqs = aws.sqs()
            def queueUrl = sqs.createQueue(b -> b.queueName(topic)).queueUrl().replace(AWSContainer.CONTAINER_ALIAS, 'localhost')

            def sns = aws.sns()
            def topicArn = sns.createTopic(b -> b.name(topic)).topicArn()
            sns.subscribe(b -> b.topicArn(topicArn).protocol("sqs").endpoint(queueUrl).returnSubscriptionArn(true).build())

            def cnt = connectorContainer(ConnectorSupport.CONTAINER_IMAGE_SNS, """
                - route:
                    from:
                      uri: kamelet:kafka-not-secured-source
                      parameters:
                        topic: ${topic}
                        bootstrapServers: ${kafka.outsideBootstrapServers}
                        groupId: ${group}
                        autoOffsetReset: "earliest"
                    steps:
                    - removeHeader:
                        name: "kafka.HEADERS"
                    - to:
                        uri: "log:s?multiLine=true&showHeaders=true"
                    - to:
                        uri: kamelet:aws-sns-sink
                        parameters:
                          accessKey: ${aws.credentials.accessKeyId()}
                          secretKey: ${aws.credentials.secretAccessKey()}
                          region: ${aws.region}
                          topicNameOrArn: ${topic}
                          autoCreateTopic: true
                          uriEndpointOverride: ${aws.endpoint}
                          overrideEndpoint: true
                """)

            cnt.start()
        when:
            kafka.send(topic, payload, ['foo': 'bar'])
        then:
            await(10, TimeUnit.SECONDS) {
                def msg = sqs.receiveMessage(b -> b.queueUrl(queueUrl))

                if (!msg.hasMessages()) {
                    return false
                }

                def msgPayload = new JsonSlurper().parseText(msg.messages().get(0).body())
                return msgPayload.Message == payload
            }
        cleanup:
            closeQuietly(cnt)
    }

    // ********************************************
    //
    // SQS
    //
    // ********************************************


    def "sqs sink"() {
        setup:
            def payload = '''{ "username":"oscerd", "city":"Rome" }'''
            def topic = UUID.randomUUID().toString()
            def group = UUID.randomUUID().toString()
            def sqs = aws.sqs()
            def queueUrl = sqs.createQueue(b -> b.queueName(topic)).queueUrl().replace(AWSContainer.CONTAINER_ALIAS, 'localhost')

            def cnt = connectorContainer(ConnectorSupport.CONTAINER_IMAGE_SQS, """
                - route:
                    from:
                      uri: kamelet:kafka-not-secured-source
                      parameters:
                        topic: ${topic}
                        bootstrapServers: ${kafka.outsideBootstrapServers}
                        groupId: ${group}
                        autoOffsetReset: "earliest"
                    steps:
                    - removeHeader:
                        name: "kafka.HEADERS"
                    - to:
                        uri: "log:s?multiLine=true&showHeaders=true"
                    - to:
                        uri: kamelet:aws-sqs-sink
                        parameters:
                          accessKey: ${aws.credentials.accessKeyId()}
                          secretKey: ${aws.credentials.secretAccessKey()}
                          region: ${aws.region}
                          queueNameOrArn: ${topic}
                          amazonAWSHost: ${AWSContainer.CONTAINER_ALIAS}
                          autoCreateQueue: true
                          uriEndpointOverride: ${aws.endpoint}
                          overrideEndpoint: true
                """)

            cnt.start()

            def s3 = aws.s3()
        when:
            kafka.send(topic, payload, ['foo': 'bar'])
        then:
            await(10, TimeUnit.SECONDS) {
                def msg = sqs.receiveMessage(b -> b.queueUrl(queueUrl)).messages.find {
                    it.body == payload
                }

                return msg != null
            }
        cleanup:
            closeQuietly(cnt)
    }

    def "sqs source"() {
        setup:
            def payload = '''{ "username":"oscerd", "city":"Rome" }'''
            def topic = UUID.randomUUID().toString()
            def queueUrl = aws.sqs().createQueue(b -> b.queueName(topic)).queueUrl().replace(AWSContainer.CONTAINER_ALIAS, 'localhost')

            def cnt = connectorContainer(ConnectorSupport.CONTAINER_IMAGE_SQS, """
                - route:
                    from:
                      uri: kamelet:aws-sqs-source
                      parameters:
                        accessKey: ${aws.credentials.accessKeyId()}
                        secretKey: ${aws.credentials.secretAccessKey()}
                        region: ${aws.region}
                        queueNameOrArn: ${topic}
                        amazonAWSHost: ${AWSContainer.CONTAINER_ALIAS}
                        autoCreateQueue: true
                        uriEndpointOverride: ${aws.endpoint}
                        overrideEndpoint: true
                    steps:
                      - to:
                          uri: "log:s?multiLine=true&showHeaders=true"
                      - to:
                          uri: kamelet:kafka-not-secured-sink
                          parameters:
                            topic: ${topic}
                            bootstrapServers: ${kafka.outsideBootstrapServers}
                """)

            cnt.start()
        when:
            aws.sqs().sendMessage(
        b -> b.queueUrl(queueUrl).messageBody(payload)
            )
        then:
            await(10, TimeUnit.SECONDS) {
                def record = kafka.poll(cnt.containerId, topic).find {
                    it.value() == payload
                }

                return record != null
            }
        cleanup:
            closeQuietly(cnt)
    }



    // ********************************************
    //
    // Kinesis
    //
    // ********************************************

    def "kinesis sink"() {
        setup:
            def payload = '''{ "username":"oscerd", "city":"Rome" }'''
            def topic = UUID.randomUUID().toString()
            def group = UUID.randomUUID().toString()
            def kinesis = aws.kinesis()

            kinesis.createStream(b -> b.streamName(topic).shardCount(1))

            def cnt = connectorContainer(ConnectorSupport.CONTAINER_IMAGE_KINESIS, """
                - route:
                    from:
                      uri: kamelet:kafka-not-secured-source
                      parameters:
                        topic: ${topic}
                        bootstrapServers: ${kafka.outsideBootstrapServers}
                        groupId: ${group}
                        autoOffsetReset: "earliest"
                    steps:
                    - removeHeader:
                        name: "kafka.HEADERS"
                    - to:
                        uri: "log:s?multiLine=true&showHeaders=true"
                    - to:
                        uri: kamelet:aws-kinesis-sink
                        parameters:
                          accessKey: ${aws.credentials.accessKeyId()}
                          secretKey: ${aws.credentials.secretAccessKey()}
                          region: ${aws.region}
                          stream: ${topic}
                          uriEndpointOverride: ${aws.endpoint}
                          overrideEndpoint: true
                """)

            cnt.start()
        when:
            kafka.send(topic, payload, ['foo': 'bar'])
        then:
            await(10, TimeUnit.SECONDS) {
                String sharedIt

                try {
                    sharedIt = ConnectorSupport.getShardIterator(kinesis, topic)
                } catch (Exception e) {
                    return false
                }

                for (Record record : aws.kinesis().getRecords(b -> b.shardIterator(sharedIt)).records()) {
                    String data = new String(record.data().asByteArray())
                    if (payload == data) {
                        return true
                    }
                }
                return false
            }
        cleanup:
            closeQuietly(cnt)
    }

    def "kinesis source"() {
        setup:
            def payload = '''{ "username":"oscerd", "city":"Rome" }'''
            def topic = UUID.randomUUID().toString()
            def kinesis = aws.kinesis()

            def cnt = connectorContainer(ConnectorSupport.CONTAINER_IMAGE_KINESIS, """
                - route:
                    from:
                      uri: kamelet:aws-kinesis-source
                      parameters:
                          accessKey: ${aws.credentials.accessKeyId()}
                          secretKey: ${aws.credentials.secretAccessKey()}
                          region: ${aws.region}
                          stream: ${topic}
                          uriEndpointOverride: ${aws.endpoint}
                          overrideEndpoint: true
                    steps:
                      - to:
                          uri: "log:s?multiLine=true&showHeaders=true"
                      - to:
                          uri: kamelet:kafka-not-secured-sink
                          parameters:
                            topic: ${topic}
                            bootstrapServers: ${kafka.outsideBootstrapServers}
                """)

            cnt.start()
        when:
            ConnectorSupport.createStream(kinesis, topic)

            kinesis.putRecord(
                b -> b.streamName(topic)
                    .partitionKey("test")
                    .data(SdkBytes.fromString(payload, Charset.defaultCharset()))
            )
        then:
            await(10, TimeUnit.SECONDS) {
                def record = kafka.poll(cnt.containerId, topic).find {
                    it.value() == payload
                }

                return record != null
            }
        cleanup:
            closeQuietly(cnt)
    }

}
