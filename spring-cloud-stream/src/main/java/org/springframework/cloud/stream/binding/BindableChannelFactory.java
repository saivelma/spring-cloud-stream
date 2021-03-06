/*
 * Copyright 2015 the original author or authors.
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

package org.springframework.cloud.stream.binding;

import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.SubscribableChannel;

/**
 * Defines methods to create/configure the {@link org.springframework.messaging.MessageChannel}s defined
 * in {@link org.springframework.cloud.stream.annotation.EnableBinding}.
 *
 * @author Ilayaperumal Gopinathan
 */
public interface BindableChannelFactory {

	/**
	 * Create a {@link SubscribableChannel} that will be bound via the message channel
	 * {@link org.springframework.cloud.stream.binder.Binder}.
	 *
	 * @param name name of the message channel
	 * @return subscribable message channel
	 */
	SubscribableChannel createSubscribableChannel(String name);

	/**
	 * Create a {@link PollableChannel} that will be bound via the message channel
	 * {@link org.springframework.cloud.stream.binder.Binder}.
	 *
	 * @param name name of the message channel
	 * @return pollable message channel
	 */
	PollableChannel createPollableChannel(String name);

}
