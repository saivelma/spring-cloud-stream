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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.cloud.stream.aggregate.SharedChannelRegistry;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.cloud.stream.binder.MessageChannelBinderSupport;
import org.springframework.cloud.stream.converter.AbstractFromMessageConverter;
import org.springframework.cloud.stream.converter.ByteArrayToStringMessageConverter;
import org.springframework.cloud.stream.converter.CompositeMessageConverterFactory;
import org.springframework.cloud.stream.converter.JavaToSerializedMessageConverter;
import org.springframework.cloud.stream.converter.JsonToPojoMessageConverter;
import org.springframework.cloud.stream.converter.JsonToTupleMessageConverter;
import org.springframework.cloud.stream.converter.MessageConverterUtils;
import org.springframework.cloud.stream.converter.PojoToJsonMessageConverter;
import org.springframework.cloud.stream.converter.PojoToStringMessageConverter;
import org.springframework.cloud.stream.converter.SerializedToJavaMessageConverter;
import org.springframework.cloud.stream.converter.StringToByteArrayMessageConverter;
import org.springframework.cloud.stream.converter.TupleToJsonMessageConverter;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.integration.channel.AbstractMessageChannel;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.ConsumerEndpointFactoryBean;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.scheduling.support.PeriodicTrigger;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MimeType;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link FactoryBean} for instantiating the interfaces specified via
 * {@link EnableBinding}
 *
 * @author Marius Bogoevici
 * @author David Syer
 * @author Ilayaperumal Gopinathan
 *
 * @see EnableBinding
 */
public class BindableProxyFactory implements MethodInterceptor, FactoryBean<Object>,
		BeanFactoryAware, EnvironmentAware, Bindable, InitializingBean {

	private static Log log = LogFactory.getLog(BindableProxyFactory.class);

	public static final String SPRING_CLOUD_STREAM_INTERNAL_PREFIX = "spring.cloud.stream.internal";

	public static final String CHANNEL_NAMESPACE_PROPERTY_NAME = SPRING_CLOUD_STREAM_INTERNAL_PREFIX + ".channelNamespace";

	public static final String POLLABLE_BRIDGE_INTERVAL_PROPERTY_NAME = SPRING_CLOUD_STREAM_INTERNAL_PREFIX + ".pollableBridge.interval";

	private Class<?> type;

	@Value("${" + CHANNEL_NAMESPACE_PROPERTY_NAME + ":}")
	private String channelNamespace;

	@Value("${" + POLLABLE_BRIDGE_INTERVAL_PROPERTY_NAME + ":1000}")
	private int pollableBridgeDefaultFrequency;

	private Object proxy = null;

	private Map<String, ChannelHolder> inputs = new HashMap<>();

	private Map<String, ChannelHolder> outputs = new HashMap<>();

	private ConfigurableListableBeanFactory beanFactory;

	private ConfigurableEnvironment environment;

	@Autowired(required = false)
	private SharedChannelRegistry sharedChannelRegistry;

	private CompositeMessageConverterFactory messageConverterFactory;

	public BindableProxyFactory(Class<?> type) {
		this.type = type;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = (ConfigurableEnvironment) environment;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		Assert.notNull(beanFactory, "Bean factory cannot be empty");
		List<AbstractFromMessageConverter> messageConverters = new ArrayList<>();
		messageConverters.add(new JsonToTupleMessageConverter());
		messageConverters.add(new TupleToJsonMessageConverter());
		messageConverters.add(new JsonToPojoMessageConverter());
		messageConverters.add(new PojoToJsonMessageConverter());
		messageConverters.add(new ByteArrayToStringMessageConverter());
		messageConverters.add(new StringToByteArrayMessageConverter());
		messageConverters.add(new PojoToStringMessageConverter());
		messageConverters.add(new JavaToSerializedMessageConverter());
		messageConverters.add(new SerializedToJavaMessageConverter());
		this.messageConverterFactory = new CompositeMessageConverterFactory(messageConverters);
	}

	private void createChannels(Class<?> type) throws Exception {
		ReflectionUtils.doWithMethods(type, new ReflectionUtils.MethodCallback() {
			@Override
			public void doWith(Method method) throws IllegalArgumentException,
					IllegalAccessException {

				Input input = AnnotationUtils.findAnnotation(method, Input.class);
				if (input != null) {
					String name = BindingBeanDefinitionRegistryUtils.getChannelName(
							input, method);
					Class<?> inputChannelType = method.getReturnType();
					MessageChannel sharedChannel = locateSharedChannel(name);
					if (sharedChannel == null) {
						MessageChannel inputChannel = createMessageChannel(inputChannelType, true);
						inputs.put(name, new ChannelHolder(inputChannel, true));
					}
					else {
						if (inputChannelType.isAssignableFrom(sharedChannel.getClass())) {
							inputs.put(name, new ChannelHolder(sharedChannel, false));
						}
						else {
							// handle the special case where the shared channel is of a different nature
							// (i.e. pollable vs subscribable) than the target channel
							final MessageChannel inputChannel = createMessageChannel(inputChannelType, true);
							if (isPollable(sharedChannel.getClass())) {
								bridgePollableToSubscribableChannel(sharedChannel,
										inputChannel);
							}
							else {
								bridgeSubscribableToPollableChannel(
										(SubscribableChannel) sharedChannel, inputChannel);
							}
							inputs.put(name, new ChannelHolder(inputChannel, false));
						}
					}
				}

				Output output = AnnotationUtils.findAnnotation(method, Output.class);
				if (output != null) {
					String name = BindingBeanDefinitionRegistryUtils.getChannelName(
							output, method);
					Class<?> messageChannelType = method.getReturnType();
					MessageChannel sharedChannel = locateSharedChannel(name);
					if (sharedChannel == null) {
						MessageChannel outputChannel = createMessageChannel(messageChannelType, false);
						outputs.put(name, new ChannelHolder(outputChannel, true));
					}
					else {
						if (messageChannelType.isAssignableFrom(sharedChannel.getClass())) {
							outputs.put(name, new ChannelHolder(sharedChannel, false));
						}
						else {
							// handle the special case where the shared channel is of a different nature
							// (i.e. pollable vs subscribable) than the target channel
							final MessageChannel outputChannel = createMessageChannel(messageChannelType, false);
							if (isPollable(messageChannelType)) {
								bridgePollableToSubscribableChannel(outputChannel,
										sharedChannel);
							}
							else {
								bridgeSubscribableToPollableChannel(
										(SubscribableChannel) outputChannel,
										sharedChannel);
							}
							outputs.put(name, new ChannelHolder(outputChannel, false));
						}
					}
				}
			}
		});
	}

	private MessageChannel locateSharedChannel(String name) {
		return sharedChannelRegistry != null ? sharedChannelRegistry.get(getNamespacePrefixedChannelName(name)) : null;
	}

	private String getNamespacePrefixedChannelName(String name) {
		return channelNamespace + "." + name;
	}

	private void bridgeSubscribableToPollableChannel(SubscribableChannel sharedChannel,
			MessageChannel inputChannel) {
		sharedChannel.subscribe(new MessageChannelBinderSupport.DirectHandler(
				inputChannel));
	}

	private void bridgePollableToSubscribableChannel(MessageChannel pollableChannel,
			MessageChannel subscribableChannel) {
		ConsumerEndpointFactoryBean consumerEndpointFactoryBean = new ConsumerEndpointFactoryBean();
		consumerEndpointFactoryBean.setInputChannel(pollableChannel);
		PollerMetadata pollerMetadata = new PollerMetadata();
		pollerMetadata.setTrigger(new PeriodicTrigger(pollableBridgeDefaultFrequency));
		consumerEndpointFactoryBean.setPollerMetadata(pollerMetadata);
		consumerEndpointFactoryBean
				.setHandler(new MessageChannelBinderSupport.DirectHandler(
						subscribableChannel));
		consumerEndpointFactoryBean.setBeanFactory(beanFactory);
		try {
			consumerEndpointFactoryBean.afterPropertiesSet();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		consumerEndpointFactoryBean.start();
	}

	private MessageChannel createMessageChannel(Class<?> messageChannelType, boolean isInput) {
		AbstractMessageChannel messageChannel = (AbstractMessageChannel)(isPollable(messageChannelType) ? new QueueChannel() : new DirectChannel());
		String contentType = null;
		if (isInput) {
			contentType = environment.resolvePlaceholders("${spring.cloud.stream.bindings.inputType:}");
		}
		else {
			contentType = environment.resolvePlaceholders("${spring.cloud.stream.bindings.outputType:}");
		}
		if (StringUtils.hasText(contentType)) {
			MimeType mimeType = getMimeType(contentType);
			MessageConverter messageConverter = messageConverterFactory.newInstance(mimeType);
			Class<?> dataType = MessageConverterUtils.getJavaTypeForContentType(mimeType, Thread.currentThread().getContextClassLoader());
			messageChannel.setDatatypes(dataType);
			messageChannel.setMessageConverter(messageConverter);
		}
		return messageChannel;
	}

	private static MimeType getMimeType(String contentTypeString) {
		MimeType mimeType = null;
		if (StringUtils.hasText(contentTypeString)) {
			try {
				mimeType = resolveContentType(contentTypeString);
			}
			catch (ClassNotFoundException cfe) {
				throw new IllegalArgumentException("Could not find the class required for " + contentTypeString, cfe);
			}
		}
		return mimeType;
	}

	public static MimeType resolveContentType(String type) throws ClassNotFoundException, LinkageError {
		if (!type.contains("/")) {
			Class<?> javaType = resolveJavaType(type);
			return MessageConverterUtils.javaObjectMimeType(javaType);
		}
		return MimeType.valueOf(type);
	}

	private static Class<?> resolveJavaType(String type) throws ClassNotFoundException, LinkageError {
		return ClassUtils.forName(type, Thread.currentThread().getContextClassLoader());
	}

	private boolean isPollable(Class<?> channelType) {
		return PollableChannel.class.equals(channelType);
	}

	@Override
	public synchronized Object invoke(MethodInvocation invocation) throws Throwable {
		Method method = invocation.getMethod();
		if (MessageChannel.class.isAssignableFrom(method.getReturnType())) {
			Input input = AnnotationUtils.findAnnotation(method, Input.class);
			if (input != null) {
				String name = BindingBeanDefinitionRegistryUtils.getChannelName(input,
						method);
				return this.inputs.get(name).getMessageChannel();
			}
			Output output = AnnotationUtils.findAnnotation(method, Output.class);
			if (output != null) {
				String name = BindingBeanDefinitionRegistryUtils.getChannelName(output,
						method);
				return this.outputs.get(name).getMessageChannel();
			}
		}
		// ignore
		return null;
	}

	@Override
	public synchronized Object getObject() throws Exception {
		if (this.proxy == null) {
			createChannels(this.type);
			ProxyFactory factory = new ProxyFactory(type, this);
			this.proxy = factory.getProxy();
		}
		return this.proxy;
	}

	@Override
	public Class<?> getObjectType() {
		return this.type;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	@Override
	public void bindInputs(ChannelBindingService channelBindingService) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("Binding inputs for %s:%s", this.channelNamespace, this.type));
		}
		for (Map.Entry<String, ChannelHolder> channelHolderEntry : inputs.entrySet()) {
			ChannelHolder channelHolder = channelHolderEntry.getValue();
			if (channelHolder.isBindable()) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Binding %s:%s:%s", this.channelNamespace, this.type, channelHolderEntry.getKey()));
				}
				channelBindingService.bindConsumer(
						channelHolder.getMessageChannel(), channelHolderEntry.getKey());
			}
		}
	}

	@Override
	public void bindOutputs(ChannelBindingService channelBindingService) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("Binding outputs for %s:%s", this.channelNamespace, this.type));
		}
		for (Map.Entry<String, ChannelHolder> channelHolderEntry : outputs.entrySet()) {
			if (channelHolderEntry.getValue().isBindable()) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Binding %s:%s:%s", this.channelNamespace, this.type, channelHolderEntry.getKey()));
				}
				channelBindingService.bindProducer(channelHolderEntry.getValue()
						.getMessageChannel(), channelHolderEntry.getKey());
			}
		}
	}

	@Override
	public void unbindInputs(ChannelBindingService channelBindingService) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("Unbinding inputs for %s:%s", this.channelNamespace, this.type));
		}
		for (Map.Entry<String, ChannelHolder> channelHolderEntry : inputs.entrySet()) {
			if (channelHolderEntry.getValue().isBindable()) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Unbinding %s:%s:%s", this.channelNamespace, this.type, channelHolderEntry.getKey()));
				}
				channelBindingService.unbindConsumers(channelHolderEntry.getKey());
			}
		}
	}

	@Override
	public void unbindOutputs(ChannelBindingService channelBindingService) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("Unbinding outputs for %s:%s", this.channelNamespace, this.type));
		}
		for (Map.Entry<String, ChannelHolder> channelHolderEntry : outputs.entrySet()) {
			if (channelHolderEntry.getValue().isBindable()) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Binding %s:%s:%s", this.channelNamespace, this.type, channelHolderEntry.getKey()));
				}
				channelBindingService.unbindProducers(channelHolderEntry.getKey());
			}
		}
	}

	/**
	 * Holds information about the channels exposed by the interface proxy, as well as
	 * their status.
	 *
	 */
	static class ChannelHolder {

		private MessageChannel messageChannel;

		private boolean bindable;

		public ChannelHolder(MessageChannel messageChannel, boolean bindable) {
			this.messageChannel = messageChannel;
			this.bindable = bindable;
		}

		public MessageChannel getMessageChannel() {
			return messageChannel;
		}

		public boolean isBindable() {
			return bindable;
		}
	}

}
