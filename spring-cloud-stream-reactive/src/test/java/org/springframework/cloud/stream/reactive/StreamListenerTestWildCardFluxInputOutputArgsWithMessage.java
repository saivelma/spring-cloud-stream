/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.reactive;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import reactor.core.publisher.Flux;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.messaging.Processor;
import org.springframework.cloud.stream.test.binder.MessageCollector;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * @author Ilayaperumal Gopinathan
 */
public class StreamListenerTestWildCardFluxInputOutputArgsWithMessage {

	@Test
	public void testWildCardFluxInputOutputArgsWithMessage() throws Exception {
		ConfigurableApplicationContext context = SpringApplication.run(TestWildCardFluxInputOutputArgsWithMessage1.class, "--server.port=0");
		sendMessageAndValidate(context);
		context.close();
	}

	@Test
	public void testInputAsStreamListenerAndOutputAsParameterUsage() {
		try {
			SpringApplication.run(TestWildCardFluxInputOutputArgsWithMessage2.class, "--server.port=0");
			fail("IllegalStateException should have been thrown");
		}
		catch (Exception e) {
			assertThat(e.getMessage()).contains("Cannot set StreamListener value 'input' when using @Output annotation as method parameter. " +
					"Use @Input method parameter annotation to specify inbound value instead");
		}
	}

	@Test
	public void testIncorrectUsage1() throws Exception {
		try {
			SpringApplication.run(TestWildCardFluxInputOutputArgsWithMessage3.class, "--server.port=0");
			fail("IllegalStateException should have been thrown");
		}
		catch (Exception e) {
			assertThat(e.getMessage()).contains("Declarative StreamListener method should only have inbound or outbound targets as method parameters");
		}
	}

	@Test
	public void testIncorrectUsage2() throws Exception {
		try {
			SpringApplication.run(TestWildCardFluxInputOutputArgsWithMessage4.class, "--server.port=0");
			fail("IllegalStateException should have been thrown");
		}
		catch (Exception e) {
			assertThat(e.getMessage()).contains("Declarative StreamListener method should only " +
					"have inbound or outbound targets as method parameters");
		}
	}

	private static void sendMessageAndValidate(ConfigurableApplicationContext context) throws InterruptedException {
		@SuppressWarnings("unchecked")
		Processor processor = context.getBean(Processor.class);
		String sentPayload = "hello " + UUID.randomUUID().toString();
		processor.input().send(MessageBuilder.withPayload(sentPayload).setHeader("contentType", "text/plain").build());
		MessageCollector messageCollector = context.getBean(MessageCollector.class);
		Message<?> result = messageCollector.forChannel(processor.output()).poll(1000, TimeUnit.MILLISECONDS);
		assertThat(result).isNotNull();
		assertThat(result.getPayload()).isEqualTo(sentPayload.toUpperCase());
	}

	@EnableBinding(Processor.class)
	@EnableAutoConfiguration
	public static class TestWildCardFluxInputOutputArgsWithMessage1 {

		@StreamListener
		public void receive(@Input(Processor.INPUT) Flux<?> input,
				@Output(Processor.OUTPUT) FluxSender output) {
			output.send(input.map(m -> MessageBuilder.withPayload(m.toString().toUpperCase()).build()));
		}
	}

	@EnableBinding(Processor.class)
	@EnableAutoConfiguration
	public static class TestWildCardFluxInputOutputArgsWithMessage2 {

		@StreamListener(Processor.INPUT)
		public void receive(Flux<?> input, @Output(Processor.OUTPUT) FluxSender output) {
			output.send(input.map(m -> MessageBuilder.withPayload(m.toString().toUpperCase()).build()));
		}
	}

	@EnableBinding(Processor.class)
	@EnableAutoConfiguration
	public static class TestWildCardFluxInputOutputArgsWithMessage3 {

		@StreamListener(Processor.INPUT)
		@SendTo(Processor.OUTPUT)
		public void receive(Flux<?> input, FluxSender output) {
			output.send(input.map(m -> MessageBuilder.withPayload(m.toString().toUpperCase()).build()));
		}
	}

	@EnableBinding(Processor.class)
	@EnableAutoConfiguration
	public static class TestWildCardFluxInputOutputArgsWithMessage4 {

		@StreamListener
		@SendTo(Processor.OUTPUT)
		public void receive(@Input(Processor.INPUT) Flux<?> input, FluxSender output) {
			output.send(input.map(m -> MessageBuilder.withPayload(m.toString().toUpperCase()).build()));
		}
	}
}