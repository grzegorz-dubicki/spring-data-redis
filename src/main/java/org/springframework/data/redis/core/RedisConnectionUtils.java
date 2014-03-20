/*
 * Copyright 2011-2014 the original author or authors.
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
package org.springframework.data.redis.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.support.ResourceHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

/**
 * Helper class featuring {@link RedisConnection} handling, allowing for reuse of instances within
 * 'transactions'/scopes.
 * 
 * @author Costin Leau
 * @author Christoph Strobl
 */
public abstract class RedisConnectionUtils {

	private static final Log log = LogFactory.getLog(RedisConnectionUtils.class);

	/**
	 * Binds a new Redis connection (from the given factory) to the current thread, if none is already bound.
	 * 
	 * @param factory connection factory
	 * @return a new Redis connection
	 */
	public static RedisConnection bindConnection(RedisConnectionFactory factory, boolean enableTranactionSupport) {
		return doGetConnection(factory, true, true, enableTranactionSupport);
	}

	/**
	 * Gets a Redis connection from the given factory. Is aware of and will return any existing corresponding connections
	 * bound to the current thread, for example when using a transaction manager. Will always create a new connection
	 * otherwise.
	 * 
	 * @param factory connection factory for creating the connection
	 * @param enableTranactionSupport
	 * @return an active Redis connection
	 */
	public static RedisConnection getConnection(RedisConnectionFactory factory, boolean enableTranactionSupport) {
		return doGetConnection(factory, true, false, enableTranactionSupport);
	}

	/**
	 * Gets a Redis connection. Is aware of and will return any existing corresponding connections bound to the current
	 * thread, for example when using a transaction manager. Will create a new Connection otherwise, if
	 * {@code allowCreate} is <tt>true</tt>.
	 * 
	 * @param factory connection factory for creating the connection
	 * @param allowCreate whether a new (unbound) connection should be created when no connection can be found for the
	 *          current thread
	 * @param bind binds the connection to the thread, in case one was created
	 * @param enableTransactionSupport
	 * @return an active Redis connection
	 */
	public static RedisConnection doGetConnection(RedisConnectionFactory factory, boolean allowCreate, boolean bind,
			boolean enableTransactionSupport) {

		Assert.notNull(factory, "No RedisConnectionFactory specified");

		RedisConnectionHolder connHolder = (RedisConnectionHolder) TransactionSynchronizationManager.getResource(factory);

		if (connHolder != null) {
			if (enableTransactionSupport) {
				potentiallyRegisterTransactionSynchronisation(connHolder, factory);
			}
			return connHolder.getConnection();
		}

		if (!allowCreate) {
			throw new IllegalArgumentException("No connection found and allowCreate = false");
		}

		if (log.isDebugEnabled()) {
			log.debug("Opening RedisConnection");
		}

		RedisConnection conn = factory.getConnection();

		if (bind) {

			RedisConnection connectionToBind = conn;
			if (enableTransactionSupport && isActualNonReadonlyTransactionActive()) {
				connectionToBind = (RedisConnection) createConnectionProxy(conn, factory);
			}

			connHolder = new RedisConnectionHolder(connectionToBind);

			TransactionSynchronizationManager.bindResource(factory, connHolder);
			if (enableTransactionSupport) {
				potentiallyRegisterTransactionSynchronisation(connHolder, factory);
			}
			return connHolder.getConnection();
		}
		return conn;
	}

	private static void potentiallyRegisterTransactionSynchronisation(final RedisConnectionHolder connHolder,
			RedisConnectionFactory factory) {

		if (isActualNonReadonlyTransactionActive()) {

			final RedisConnection connection = connHolder.getConnection();

			if (!connHolder.isTransactionSyncronisationActive()) {
				connHolder.setTransactionSyncronisationActive(true);
				connection.multi();

				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
					@Override
					public void afterCompletion(int status) {
						if (TransactionSynchronization.STATUS_COMMITTED == status) {
							connection.exec();
						}
						if (TransactionSynchronization.STATUS_ROLLED_BACK == status) {
							connection.discard();
						}
						connHolder.setTransactionSyncronisationActive(false);
					}
				});
			}
		}
	}

	private static boolean isActualNonReadonlyTransactionActive() {
		return TransactionSynchronizationManager.isActualTransactionActive()
				&& !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
	}

	private static Object createConnectionProxy(RedisConnection connection, RedisConnectionFactory factory) {

		ProxyFactory proxyFactory = new ProxyFactory(connection);
		ConnectionSplittingInterceptor interceptor = new ConnectionSplittingInterceptor(factory);

		proxyFactory.addAdvice(interceptor);

		return proxyFactory.getProxy();
	}

	/**
	 * Closes the given connection, created via the given factory if not managed externally (i.e. not bound to the
	 * thread).
	 * 
	 * @param conn the Redis connection to close
	 * @param factory the Redis factory that the connection was created with
	 */
	public static void releaseConnection(RedisConnection conn, RedisConnectionFactory factory) {
		if (conn == null) {
			return;
		}
		// Only release non-transactional/non-bound connections.
		if (!isConnectionTransactional(conn, factory)) {
			if (log.isDebugEnabled()) {
				log.debug("Closing Redis Connection");
			}
			conn.close();
		}
	}

	/**
	 * Unbinds and closes the connection (if any) associated with the given factory.
	 * 
	 * @param factory Redis factory
	 */
	public static void unbindConnection(RedisConnectionFactory factory) {
		RedisConnectionHolder connHolder = (RedisConnectionHolder) TransactionSynchronizationManager
				.unbindResourceIfPossible(factory);
		if (connHolder != null) {
			RedisConnection connection = connHolder.getConnection();
			connection.close();
		}
	}

	/**
	 * Return whether the given Redis connection is transactional, that is, bound to the current thread by Spring's
	 * transaction facilities.
	 * 
	 * @param conn Redis connection to check
	 * @param connFactory Redis connection factory that the connection was created with
	 * @return whether the connection is transactional or not
	 */
	public static boolean isConnectionTransactional(RedisConnection conn, RedisConnectionFactory connFactory) {
		if (connFactory == null) {
			return false;
		}
		RedisConnectionHolder connHolder = (RedisConnectionHolder) TransactionSynchronizationManager
				.getResource(connFactory);
		return (connHolder != null && conn == connHolder.getConnection());
	}

	/**
	 * @author Christoph Strobl
	 * @since 1.3
	 */
	static class ConnectionSplittingInterceptor implements MethodInterceptor,
			org.springframework.cglib.proxy.MethodInterceptor {

		private final RedisConnectionFactory factory;

		public ConnectionSplittingInterceptor(RedisConnectionFactory factory) {
			this.factory = factory;
		}

		@Override
		public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {

			RedisCommand commandToExecute = failsafeRedisCommandEvaluation(method.getName());

			if (isPotentiallyThreadBoundCommand(commandToExecute)) {
				log.debug(String.format("Invoke '%s' on bound conneciton", method.getName()));
				return invoke(method, obj, args);
			}

			log.debug(String.format("Invoke '%s' on unbound conneciton", method.getName()));
			RedisConnection connection = factory.getConnection();
			try {
				return invoke(method, connection, args);
			} finally {
				// properly close the unbound connection after executing command
				if (!connection.isClosed()) {
					connection.close();
				}
			}
		}

		private Object invoke(Method method, Object target, Object[] args) throws Throwable {

			try {
				return method.invoke(target, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		@Override
		public Object invoke(MethodInvocation invocation) throws Throwable {
			return intercept(invocation.getThis(), invocation.getMethod(), invocation.getArguments(), null);
		}

		private boolean isPotentiallyThreadBoundCommand(RedisCommand command) {
			return RedisCommand.UNKNOWN.equals(command) || !command.isReadonly();
		}

		private RedisCommand failsafeRedisCommandEvaluation(String key) {

			for (RedisCommand command : RedisCommand.values()) {
				if (command.isRepresentedBy(key)) {
					return command;
				}
			}

			return RedisCommand.UNKNOWN;
		}

	}

	private static class RedisConnectionHolder implements ResourceHolder {

		private boolean isVoid = false;
		private final RedisConnection conn;
		private boolean isTransactionSyncronisationActive;

		public RedisConnectionHolder(RedisConnection conn) {
			this.conn = conn;
		}

		public boolean isVoid() {
			return isVoid;
		}

		public RedisConnection getConnection() {
			return conn;
		}

		public void reset() {
			// no-op
		}

		public void unbound() {
			this.isVoid = true;
		}

		/**
		 * @return
		 * @since 1.3
		 */
		public boolean isTransactionSyncronisationActive() {
			return isTransactionSyncronisationActive;
		}

		/**
		 * @param isTransactionSyncronisationActive
		 * @since 1.3
		 */
		public void setTransactionSyncronisationActive(boolean isTransactionSyncronisationActive) {
			this.isTransactionSyncronisationActive = isTransactionSyncronisationActive;
		}
	}
}
