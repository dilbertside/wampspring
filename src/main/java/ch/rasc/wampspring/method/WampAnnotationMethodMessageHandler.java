/**
 * Copyright 2002-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ch.rasc.wampspring.method;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.handler.HandlerMethodSelector;
import org.springframework.messaging.handler.annotation.support.DestinationVariableMethodArgumentResolver;
import org.springframework.messaging.handler.annotation.support.HeaderMethodArgumentResolver;
import org.springframework.messaging.handler.annotation.support.HeadersMethodArgumentResolver;
import org.springframework.messaging.handler.annotation.support.MessageMethodArgumentResolver;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolverComposite;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.PathMatcher;
import org.springframework.util.ReflectionUtils.MethodFilter;
import org.springframework.util.StringUtils;

import ch.rasc.wampspring.EventMessenger;
import ch.rasc.wampspring.annotation.WampAuthenticated;
import ch.rasc.wampspring.annotation.WampCallListener;
import ch.rasc.wampspring.annotation.WampPublishListener;
import ch.rasc.wampspring.annotation.WampSubscribeListener;
import ch.rasc.wampspring.annotation.WampUnsubscribeListener;
import ch.rasc.wampspring.config.WampMessageSelector;
import ch.rasc.wampspring.config.WampSession;
import ch.rasc.wampspring.config.WampSessionContextHolder;
import ch.rasc.wampspring.message.CallErrorMessage;
import ch.rasc.wampspring.message.CallMessage;
import ch.rasc.wampspring.message.CallResultMessage;
import ch.rasc.wampspring.message.PublishMessage;
import ch.rasc.wampspring.message.SubscribeMessage;
import ch.rasc.wampspring.message.UnsubscribeMessage;
import ch.rasc.wampspring.message.WampMessage;

/**
 * Internal class that is responsible for calling methods that are annotated with
 * {@link WampCallListener}, {@link WampPublishListener}, {@link WampSubscribeListener} or
 * {@link WampUnsubscribeListener}
 *
 * <p>
 * Supports Ant-style path patterns with template variables.
 *
 * @author Rossen Stoyanchev
 * @author Brian Clozel
 * @author Ralph Schaer
 */
public class WampAnnotationMethodMessageHandler implements MessageHandler,
		ApplicationContextAware, InitializingBean, SmartLifecycle {

	private final Object lifecycleMonitor = new Object();

	private volatile boolean running = false;

	private volatile long sendTimeout = -1;

	private final SubscribableChannel clientInboundChannel;

	private final MessageChannel clientOutboundChannel;

	private final EventMessenger eventMessenger;

	private final MethodParameterConverter methodParameterConverter;

	private final PathMatcher pathMatcher;

	private final WampMessageSelector wampMessageSelector;

	private boolean authenticationRequiredGlobal = false;

	private final Log logger = LogFactory.getLog(getClass());

	private final ConversionService conversionService;

	private final List<HandlerMethodArgumentResolver> customArgumentResolvers = new ArrayList<>(
			4);

	private final HandlerMethodArgumentResolverComposite argumentResolvers = new HandlerMethodArgumentResolverComposite();

	private ApplicationContext applicationContext;

	private final MultiValueMap<WampMessageMappingInfo, WampHandlerMethod> handlerMethods = new LinkedMultiValueMap<>();

	private final MultiValueMap<String, WampMessageMappingInfo> destinationLookup = new LinkedMultiValueMap<>();

	public WampAnnotationMethodMessageHandler(SubscribableChannel clientInboundChannel,
			MessageChannel clientOutboundChannel, EventMessenger eventMessenger,
			ConversionService conversionService,
			MethodParameterConverter methodParameterConverter, PathMatcher pathMatcher,
			WampMessageSelector wampMessageSelector) {
		this.clientInboundChannel = clientInboundChannel;
		this.clientOutboundChannel = clientOutboundChannel;
		this.eventMessenger = eventMessenger;
		this.conversionService = conversionService;
		this.methodParameterConverter = methodParameterConverter;
		this.pathMatcher = pathMatcher;
		this.wampMessageSelector = wampMessageSelector;
	}

	public void setAuthenticationRequiredGlobal(boolean authenticationRequiredGlobal) {
		this.authenticationRequiredGlobal = authenticationRequiredGlobal;
	}

	public void setSendTimeout(long sendTimeout) {
		this.sendTimeout = sendTimeout;
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE;
	}

	@Override
	public final boolean isRunning() {
		synchronized (this.lifecycleMonitor) {
			return this.running;
		}
	}

	@Override
	public final void start() {
		synchronized (this.lifecycleMonitor) {
			this.clientInboundChannel.subscribe(this);
			this.running = true;
		}
	}

	@Override
	public final void stop() {
		synchronized (this.lifecycleMonitor) {
			this.running = false;
			this.clientInboundChannel.unsubscribe(this);
		}
	}

	@Override
	public final void stop(Runnable callback) {
		synchronized (this.lifecycleMonitor) {
			stop();
			callback.run();
		}
	}

	private List<? extends HandlerMethodArgumentResolver> initArgumentResolvers() {
		ConfigurableBeanFactory beanFactory = ClassUtils.isAssignableValue(
				ConfigurableApplicationContext.class, this.applicationContext) ? ((ConfigurableApplicationContext) this.applicationContext)
				.getBeanFactory() : null;

		List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

		// Annotation-based argument resolution
		resolvers.add(new HeaderMethodArgumentResolver(this.conversionService,
				beanFactory));
		resolvers.add(new HeadersMethodArgumentResolver());
		resolvers.add(new DestinationVariableMethodArgumentResolver(
				this.conversionService));

		// Type-based argument resolution
		resolvers.add(new PrincipalMethodArgumentResolver());
		resolvers.add(new WampSessionMethodArgumentResolver());
		resolvers.add(new MessageMethodArgumentResolver());

		resolvers.addAll(getCustomArgumentResolvers());

		resolvers.add(new PayloadArgumentResolver(this.applicationContext,
				this.methodParameterConverter));

		return resolvers;
	}

	private void handleMatchInternal(WampHandlerMethod handlerMethod, WampMessage message) {

		if (this.logger.isDebugEnabled()) {
			this.logger.debug("Invoking " + handlerMethod.getShortLogMessage());
		}

		switch (message.getType()) {
		case CALL:
			handleCallMessage((CallMessage) message, handlerMethod);
			break;
		case PUBLISH:
			PublishMessage publishMessage = (PublishMessage) message;
			handlePubSubMessage(publishMessage, publishMessage.getEvent(), handlerMethod);
			break;
		case SUBSCRIBE:
			SubscribeMessage subscribeMessage = (SubscribeMessage) message;
			handlePubSubMessage(subscribeMessage, null, handlerMethod);
			break;
		case UNSUBSCRIBE:
			UnsubscribeMessage unsubscribeMessage = (UnsubscribeMessage) message;
			handlePubSubMessage(unsubscribeMessage, null, handlerMethod);
			break;
		default:
			break;
		}

	}

	private static void checkAuthentication(WampHandlerMethod handlerMethod,
			WampMessage message) {
		WampSession wampSession = message.getWampSession();
		if (wampSession != null && !wampSession.isAuthenticated()
				&& handlerMethod.isAuthenticationRequired()) {

			if (!(message instanceof UnsubscribeMessage && ((UnsubscribeMessage) message)
					.isCleanup())) {
				throw new SecurityException("Not authenticated");
			}

		}
	}

	@Override
	public void handleMessage(Message<?> message) throws MessagingException {

		if (!(message instanceof WampMessage && this.wampMessageSelector
				.accept((WampMessage) message))) {
			return;
		}

		WampMessage wampMessage = (WampMessage) message;
		String destination = wampMessage.getDestination();
		if (destination == null) {
			return;
		}

		handleMessageInternal(wampMessage, destination);
	}

	private void handleCallMessage(CallMessage callMessage,
			WampHandlerMethod handlerMethod) {
		try {
			checkAuthentication(handlerMethod, callMessage);

			InvocableWampHandlerMethod invocable = new InvocableWampHandlerMethod(
					handlerMethod.createWithResolvedBean(), this.methodParameterConverter);
			invocable.setMessageMethodArgumentResolvers(this.argumentResolvers);

			Object[] arguments = null;
			if (callMessage.getArguments() != null) {
				arguments = callMessage.getArguments().toArray();
			}
			Object returnValue = invocable.invoke(callMessage, arguments);
			CallResultMessage callResultMessage = new CallResultMessage(callMessage,
					returnValue);
			send(callResultMessage);
		}
		catch (Exception ex) {
			CallErrorMessage callErrorMessage = new CallErrorMessage(callMessage, "",
					ex.toString());
			send(callErrorMessage);
			this.logger.error("Error while processing message " + callMessage, ex);
		}
		catch (Throwable t) {
			CallErrorMessage callErrorMessage = new CallErrorMessage(callMessage, "",
					t.toString());
			send(callErrorMessage);
			this.logger.error("Error while processing message " + callErrorMessage, t);
		}
	}

	public void send(WampMessage wampMessage) {
		long timeout = this.sendTimeout;
		boolean sent = timeout >= 0 ? this.clientOutboundChannel.send(wampMessage,
				timeout) : this.clientOutboundChannel.send(wampMessage);

		if (!sent) {
			throw new MessageDeliveryException(wampMessage,
					"Failed to send message with destination '"
							+ wampMessage.getDestination() + "' within timeout: "
							+ timeout);
		}
	}

	private void handlePubSubMessage(WampMessage wampMessage, Object argument,
			WampHandlerMethod wampHandlerMethod) {

		try {
			checkAuthentication(wampHandlerMethod, wampMessage);

			InvocableWampHandlerMethod invocable = new InvocableWampHandlerMethod(
					wampHandlerMethod.createWithResolvedBean(),
					this.methodParameterConverter);
			invocable.setMessageMethodArgumentResolvers(this.argumentResolvers);

			Object returnValue = invocable.invoke(wampMessage, argument);
			if (returnValue != null) {

				for (String replyToTopicURI : wampHandlerMethod.getReplyTo()) {
					if (StringUtils.hasText(replyToTopicURI)) {
						if (wampHandlerMethod.isBroadcast()) {
							if (wampHandlerMethod.isExcludeSender()) {
								this.eventMessenger.sendToAllExcept(replyToTopicURI,
										returnValue, wampMessage.getWebSocketSessionId());
							}
							else {
								this.eventMessenger.sendToAll(replyToTopicURI,
										returnValue);
							}
						}
						else {
							if (!wampHandlerMethod.isExcludeSender()) {
								this.eventMessenger.sendTo(replyToTopicURI, returnValue,
										wampMessage.getWebSocketSessionId());
							}
						}
					}
				}
			}
		}
		catch (Throwable ex) {
			this.logger.error("Error while processing message " + wampMessage, ex);
		}

	}

	private <A extends Annotation> void detectHandlerMethods(String beanName,
			Class<?> userType, final Class<A> annotationType) {

		Set<Method> methods = HandlerMethodSelector.selectMethods(userType,
				new MethodFilter() {
					@Override
					public boolean matches(Method method) {
						return AnnotationUtils.findAnnotation(method, annotationType) != null;
					}
				});

		for (Method method : methods) {
			A annotation = AnnotationUtils.findAnnotation(method, annotationType);

			String[] replyTo = (String[]) AnnotationUtils.getValue(annotation, "replyTo");
			Boolean excludeSender = (Boolean) AnnotationUtils.getValue(annotation,
					"excludeSender");
			Boolean broadcast = (Boolean) AnnotationUtils.getValue(annotation,
					"broadcast");

			boolean authenticationRequiredClass = AnnotationUtils.findAnnotation(
					userType, WampAuthenticated.class) != null;
			boolean[] authenticationRequiredMethod = (boolean[]) AnnotationUtils
					.getValue(annotation, "authenticated");

			boolean authenticationRequired = false;
			if (authenticationRequiredMethod != null
					&& authenticationRequiredMethod.length == 1) {
				authenticationRequired = authenticationRequiredMethod[0];
			}
			else if (authenticationRequiredClass || this.authenticationRequiredGlobal) {
				authenticationRequired = true;
			}

			WampHandlerMethod newHandlerMethod = new WampHandlerMethod(beanName,
					this.applicationContext, method, replyTo, broadcast, excludeSender,
					authenticationRequired);

			String[] destinations = (String[]) AnnotationUtils.getValue(annotation);
			if (destinations.length == 0) {
				// by default use beanName.methodName as destination
				destinations = new String[] { beanName + "." + method.getName() };
			}

			WampMessageMappingInfo mapping = null;

			if (annotationType.equals(WampCallListener.class)) {
				mapping = new WampMessageMappingInfo(
						WampMessageTypeMessageCondition.CALL,
						new DestinationPatternsMessageCondition(destinations,
								this.pathMatcher));
			}
			else if (annotationType.equals(WampPublishListener.class)) {
				mapping = new WampMessageMappingInfo(
						WampMessageTypeMessageCondition.PUBLISH,
						new DestinationPatternsMessageCondition(destinations,
								this.pathMatcher));
			}
			else if (annotationType.equals(WampSubscribeListener.class)) {
				mapping = new WampMessageMappingInfo(
						WampMessageTypeMessageCondition.SUBSCRIBE,
						new DestinationPatternsMessageCondition(destinations,
								this.pathMatcher));
			}
			else if (annotationType.equals(WampUnsubscribeListener.class)) {
				mapping = new WampMessageMappingInfo(
						WampMessageTypeMessageCondition.UNSUBSCRIBE,
						new DestinationPatternsMessageCondition(destinations,
								this.pathMatcher));
			}

			registerHandlerMethod(newHandlerMethod, mapping);

		}
	}

	private void registerHandlerMethod(WampHandlerMethod newHandlerMethod,
			WampMessageMappingInfo mapping) {

		this.handlerMethods.add(mapping, newHandlerMethod);
		if (this.logger.isInfoEnabled()) {
			this.logger.info("Mapped \"" + mapping + "\" onto " + newHandlerMethod);
		}

		for (String pattern : getDirectLookupDestinations(mapping)) {
			this.destinationLookup.add(pattern, mapping);
		}
	}

	private void detectHandlerMethods(String beanName) {

		Class<?> handlerType = this.applicationContext.getType(beanName);
		final Class<?> userType = ClassUtils.getUserClass(handlerType);

		detectHandlerMethods(beanName, userType, WampCallListener.class);
		detectHandlerMethods(beanName, userType, WampPublishListener.class);
		detectHandlerMethods(beanName, userType, WampSubscribeListener.class);
		detectHandlerMethods(beanName, userType, WampUnsubscribeListener.class);
	}

	private Set<String> getDirectLookupDestinations(WampMessageMappingInfo mapping) {
		Set<String> result = new LinkedHashSet<>();
		for (String pattern : mapping.getDestinationConditions().getPatterns()) {
			if (!this.pathMatcher.isPattern(pattern)) {
				result.add(pattern);
			}
		}
		return result;
	}

	/**
	 * Sets the list of custom {@code HandlerMethodArgumentResolver}s that will be used
	 * after resolvers for supported argument type.
	 * @param customArgumentResolvers the list of resolvers; never {@code null}.
	 */
	public void setCustomArgumentResolvers(
			List<HandlerMethodArgumentResolver> customArgumentResolvers) {
		this.customArgumentResolvers.clear();
		if (customArgumentResolvers != null) {
			this.customArgumentResolvers.addAll(customArgumentResolvers);
		}
	}

	/**
	 * Return the configured custom argument resolvers, if any.
	 */
	public List<HandlerMethodArgumentResolver> getCustomArgumentResolvers() {
		return this.customArgumentResolvers;
	}

	/**
	 * Configure the complete list of supported argument types effectively overriding the
	 * ones configured by default. This is an advanced option. For most use cases it
	 * should be sufficient to use {@link #setCustomArgumentResolvers(java.util.List)}.
	 */
	public void setArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
		if (argumentResolvers == null) {
			this.argumentResolvers.clear();
			return;
		}
		this.argumentResolvers.addResolvers(argumentResolvers);
	}

	public List<HandlerMethodArgumentResolver> getArgumentResolvers() {
		return this.argumentResolvers.getResolvers();
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext)
			throws BeansException {
		this.applicationContext = applicationContext;
	}

	@Override
	public void afterPropertiesSet() {

		if (this.argumentResolvers.getResolvers().isEmpty()) {
			this.argumentResolvers.addResolvers(initArgumentResolvers());
		}

		for (String beanName : this.applicationContext.getBeanNamesForType(Object.class)) {
			detectHandlerMethods(beanName);
		}
	}

	private void handleMessageInternal(WampMessage message, String lookupDestination) {
		List<Match> matches = new ArrayList<>();

		List<WampMessageMappingInfo> mappingsByUrl = this.destinationLookup
				.get(lookupDestination);
		if (mappingsByUrl != null) {
			addMatchesToCollection(mappingsByUrl, message, matches);
		}
		if (matches.isEmpty()) {
			// No direct hits, go through all mappings
			Set<WampMessageMappingInfo> allMappings = this.handlerMethods.keySet();
			addMatchesToCollection(allMappings, message, matches);
		}
		if (matches.isEmpty()) {
			handleNoMatch(this.handlerMethods.keySet(), lookupDestination, message);
			return;
		}

		if (this.logger.isTraceEnabled()) {
			this.logger.trace("Found " + matches.size() + " methods: " + matches);
		}

		for (Match match : matches) {
			handleMatch(match.mapping, match.handlerMethod, lookupDestination, message);
		}
	}

	private void addMatchesToCollection(
			Collection<WampMessageMappingInfo> mappingsToCheck, Message<?> message,
			List<Match> matches) {
		for (WampMessageMappingInfo mapping : mappingsToCheck) {
			WampMessageMappingInfo match = mapping.getMatchingCondition(message);
			if (match != null) {
				List<WampHandlerMethod> methods = this.handlerMethods.get(mapping);
				for (WampHandlerMethod method : methods) {
					matches.add(new Match(match, method));
				}
			}
		}
	}

	private void handleMatch(WampMessageMappingInfo mapping,
			WampHandlerMethod handlerMethod, String lookupDestination, WampMessage message) {

		if (!"**".equals(message.getDestination())) {
			String matchedPattern = mapping.getDestinationConditions().getPatterns()
					.iterator().next();
			Map<String, String> vars = this.pathMatcher.extractUriTemplateVariables(
					matchedPattern, lookupDestination);

			if (!CollectionUtils.isEmpty(vars)) {
				message.setDestinationTemplateVariables(vars);
			}
		}

		try {
			WampSessionContextHolder.setAttributesFromMessage(message);
			handleMatchInternal(handlerMethod, message);
		}
		finally {
			WampSessionContextHolder.resetAttributes();
		}
	}

	@SuppressWarnings("unused")
	private void handleNoMatch(Set<WampMessageMappingInfo> ts, String lookupDestination,
			Message<?> message) {
		if (this.logger.isDebugEnabled()) {
			this.logger.debug("No matching methods.");
		}
	}

	@Override
	public String toString() {
		return getClass().getSimpleName();
	}

	/**
	 * A thin wrapper around a matched HandlerMethod and its matched mapping for the
	 * purpose of comparing the best match with a comparator in the context of a message.
	 */
	static class Match {

		final WampMessageMappingInfo mapping;

		final WampHandlerMethod handlerMethod;

		private Match(WampMessageMappingInfo mapping, WampHandlerMethod handlerMethod) {
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
		}

		@Override
		public String toString() {
			return this.mapping.toString();
		}
	}

}
