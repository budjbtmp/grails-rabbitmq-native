/*
 * Copyright 2013-2017 Bud Byrd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.budjb.rabbitmq.test.consumer

import com.budjb.rabbitmq.connection.ConnectionManager
import com.budjb.rabbitmq.consumer.LegacyConsumerContext
import com.budjb.rabbitmq.consumer.MessageContext
import com.budjb.rabbitmq.converter.*
import com.budjb.rabbitmq.exception.MissingConfigurationException
import com.budjb.rabbitmq.exception.NoMessageHandlersDefinedException
import com.budjb.rabbitmq.publisher.RabbitMessagePublisher
import com.budjb.rabbitmq.test.support.*
import com.rabbitmq.client.BasicProperties
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Envelope
import grails.config.Config
import grails.persistence.support.PersistenceContextInterceptor
import groovy.json.JsonOutput
import org.grails.config.PropertySourcesConfig
import org.springframework.context.ApplicationEventPublisher
import spock.lang.Specification
import spock.lang.Unroll

class LegacyConsumerContextSpec extends Specification {
    MessageConverterManager messageConverterManager
    PersistenceContextInterceptor persistenceContextInterceptor
    ConnectionManager connectionManager
    RabbitMessagePublisher rabbitMessagePublisher
    ApplicationEventPublisher applicationEventPublisher

    LegacyConsumerContext createContext(Object consumer, Config config) {
        return new LegacyConsumerContext(consumer, config, connectionManager, persistenceContextInterceptor, rabbitMessagePublisher, applicationEventPublisher, messageConverterManager)
    }

    def setup() {
        messageConverterManager = new MessageConverterManagerImpl()
        messageConverterManager.register(new JsonMessageConverter())
        messageConverterManager.register(new LongMessageConverter())
        messageConverterManager.register(new StringMessageConverter())
    }

    def 'If a consumer has a configuration defined in the application config, it is loaded correctly'() {
        setup:
        Config config = new PropertySourcesConfig([
            rabbitmq: [
                consumers: [
                    'UnitTestConsumer': [
                        queue    : 'test-queue',
                        consumers: 10
                    ]
                ]
            ]
        ])

        LegacyConsumerContext consumer = createContext(new UnitTestConsumer(), config)

        expect:
        consumer.id == 'com.budjb.rabbitmq.test.support.UnitTestConsumer'
        consumer.consumerConfiguration.queue == 'test-queue'
        consumer.consumerConfiguration.consumers == 10
    }

    def 'If a consumer has a configuration defined within the object, it is loaded correctly'() {
        setup:
        LegacyConsumerContext consumer = createContext(new UnitTestConsumer(), new PropertySourcesConfig())

        expect:
        consumer.id == 'com.budjb.rabbitmq.test.support.UnitTestConsumer'
        consumer.consumerConfiguration.queue == 'test-queue'
        consumer.consumerConfiguration.consumers == 5
    }

    def 'If a consumer has no configuration defined, a MissingConfigurationException is thrown'() {
        when:
        createContext(new MissingConfigurationConsumer(), new PropertySourcesConfig())

        then:
        thrown MissingConfigurationException
    }

    def 'If a consumer has no message handlers defined, a NoMessageHandlersDefinedException is thrown'() {
        when:
        createContext(new MissingHandlersConsumer(), new PropertySourcesConfig())

        then:
        thrown NoMessageHandlersDefinedException
    }

    @Unroll
    def 'When a #type type is given to the MultipleMessageConsumer, the proper handler is called'() {
        setup:
        MultipleHandlersConsumer wrapped = new MultipleHandlersConsumer()

        LegacyConsumerContext consumer = createContext(wrapped, new PropertySourcesConfig())

        MessageContext messageContext = new MessageContext()
        messageContext.body = body
        messageContext.channel = Mock(Channel)
        messageContext.envelope = Mock(Envelope)
        messageContext.consumerTag = 'foobar'
        messageContext.properties = Mock(BasicProperties)

        when:
        consumer.process(messageContext)

        then:
        wrapped.handler == handler

        where:
        type      | body                                  | handler
        'map'     | JsonOutput.toJson([foo: 'bar']).bytes | MultipleHandlersConsumer.Handler.MAP
        'integer' | 1234.toString().bytes                 | MultipleHandlersConsumer.Handler.INTEGER
        'b[]'     | [1, 2, 3] as byte[]                   | MultipleHandlersConsumer.Handler.BYTE
    }

    def 'Validate that the handlers of wrapped consumers are called'() {
        setup:
        WrappedMessageConsumer wrappedMessageConsumer = new WrappedMessageConsumer()

        LegacyConsumerContext messageConsumer = createContext(wrappedMessageConsumer, new PropertySourcesConfig())
        MessageContext messageContext = Mock(MessageContext)

        when: 'the wrapper\'s onReceive is called'
        messageConsumer.onReceive(messageContext)

        then: 'the wrapped consumer\'s onReceive is called'
        wrappedMessageConsumer.callback == WrappedMessageConsumer.Callback.ON_RECEIVE

        when: 'the wrapper\'s onSuccess is called'
        messageConsumer.onSuccess(messageContext)

        then: 'the wrapped consumer\'s onSuccess is called'
        wrappedMessageConsumer.callback == WrappedMessageConsumer.Callback.ON_SUCCESS

        when: 'the wrapper\'s onFailure is called'
        messageConsumer.onFailure(messageContext, new Exception())

        then: 'the wrapped consumer\'s onFailure is called'
        wrappedMessageConsumer.callback == WrappedMessageConsumer.Callback.ON_FAILURE

        when: 'the wrapper\'s onComplete is called'
        messageConsumer.onComplete(messageContext)

        then: 'the wrapped consumer\'s onComplete is called'
        wrappedMessageConsumer.callback == WrappedMessageConsumer.Callback.ON_COMPLETE
    }
}
