/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.gateway.service.endpoints.directives.auth;

import static org.apache.pekko.http.javadsl.server.Directives.extractRequestContext;
import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.apache.pekko.http.javadsl.model.Uri;
import org.apache.pekko.http.javadsl.server.Directives;
import org.apache.pekko.http.javadsl.server.Route;
import org.eclipse.ditto.base.model.exceptions.DittoRuntimeException;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.edge.service.headers.DittoHeadersValidator;
import org.eclipse.ditto.gateway.api.GatewayAuthenticationFailedException;
import org.eclipse.ditto.gateway.service.security.authentication.AuthenticationChain;
import org.eclipse.ditto.gateway.service.security.authentication.AuthenticationResult;
import org.eclipse.ditto.internal.utils.pekko.logging.DittoLogger;
import org.eclipse.ditto.internal.utils.pekko.logging.DittoLoggerFactory;
import org.eclipse.ditto.internal.utils.tracing.DittoTracing;
import org.eclipse.ditto.internal.utils.tracing.TraceUtils;
import org.eclipse.ditto.internal.utils.tracing.span.SpanOperationName;
import org.eclipse.ditto.internal.utils.tracing.span.SpanTagKey;

import scala.util.Try;

/**
 * Pekko Http directive which performs authentication for the gateway.
 */
public final class GatewayAuthenticationDirective {

    private static final DittoLogger LOGGER = DittoLoggerFactory.getLogger(GatewayAuthenticationDirective.class);

    private final AuthenticationChain authenticationChain;
    private final Function<DittoHeaders, DittoRuntimeException> defaultUnauthorizedExceptionFactory;

    /**
     * Constructor.
     *
     * @param authenticationChain the authentication chain that should be applied.
     * @throws NullPointerException if authenticationChain is {@code null}.
     */
    public GatewayAuthenticationDirective(final AuthenticationChain authenticationChain) {
        this(authenticationChain, dittoHeaders -> GatewayAuthenticationFailedException.newBuilder("Unauthorized.")
                .dittoHeaders(dittoHeaders)
                .build());
    }

    /**
     * Constructor.
     *
     * @param authenticationChain the authentication chain that should be applied.
     * @param defaultUnauthorizedExceptionFactory a function that creates a DittoRuntimeException that should be
     * returned if {@code authenticationChain} did not contain an applicable AuthenticationProvider.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public GatewayAuthenticationDirective(final AuthenticationChain authenticationChain,
            final Function<DittoHeaders, DittoRuntimeException> defaultUnauthorizedExceptionFactory) {

        this.authenticationChain = checkNotNull(authenticationChain, "authenticationChain");
        this.defaultUnauthorizedExceptionFactory =
                checkNotNull(defaultUnauthorizedExceptionFactory, "defaultUnauthorizedExceptionFactory");
    }

    /**
     * Depending on the request headers, one of the supported authentication mechanisms is applied.
     *
     * @param dittoHeaders the DittoHeaders containing already gathered context information.
     * @param dittoHeadersValidator a validator making sure that DittoHeader size is not exceeded
     * @param inner the inner route which will be wrapped with the {@link DittoHeaders}.
     * @return the inner route.
     */
    public Route authenticate(final DittoHeaders dittoHeaders, final DittoHeadersValidator dittoHeadersValidator,
            final Function<AuthenticationResult, Route> inner) {
        return extractRequestContext(requestContext -> {
            final Uri requestUri = requestContext.getRequest().getUri();

            final CompletableFuture<AuthenticationResult> authenticationResult =
                    authenticationChain.authenticate(requestContext, dittoHeaders)
                            .thenCompose(authRes ->
                                dittoHeadersValidator.validate(authRes.getDittoHeaders())
                                        .thenApply(validHeaders -> authRes)
                            );

            final Function<Try<AuthenticationResult>, Route> handleAuthenticationTry =
                    authenticationResultTry -> handleAuthenticationTry(authenticationResultTry, requestUri,
                            dittoHeaders, inner);

            return Directives.onComplete(authenticationResult, handleAuthenticationTry);
        });
    }

    private Route handleAuthenticationTry(final Try<AuthenticationResult> authenticationResultTry,
            final Uri requestUri,
            final DittoHeaders dittoHeaders,
            final Function<AuthenticationResult, Route> inner) {

        if (authenticationResultTry.isSuccess()) {
            final AuthenticationResult authenticationResult = authenticationResultTry.get();
            if (authenticationResult.isSuccess()) {
                return inner.apply(authenticationResult);
            }
            return handleFailedAuthentication(authenticationResult.getReasonOfFailure(), requestUri, dittoHeaders);
        }
        return handleFailedAuthentication(authenticationResultTry.failed().get(), requestUri, dittoHeaders);
    }

    private Route handleFailedAuthentication(final Throwable reasonOfFailure, final Uri requestUri,
            final DittoHeaders dittoHeaders) {

        if (reasonOfFailure instanceof DittoRuntimeException dittoRuntimeException) {
            LOGGER.withCorrelationId(dittoHeaders)
                    .debug("Authentication for URI <{}> failed. Rethrow DittoRuntimeException.", requestUri,
                            reasonOfFailure);
            DittoTracing.newPreparedSpan(dittoHeaders, SpanOperationName.of(TraceUtils.FILTER_AUTH_METRIC_NAME))
                    .start()
                    .tag(SpanTagKey.AUTH_SUCCESS.getTagForValue(false))
                    .tag(SpanTagKey.AUTH_ERROR.getTagForValue(true))
                    .tagAsFailed(reasonOfFailure)
                    .finish();
            throw dittoRuntimeException;
        } else {
            LOGGER.withCorrelationId(dittoHeaders)
                    .warn("Unexpected authentication failure for URI <{}>: <{}: {}>", requestUri,
                            reasonOfFailure.getClass().getSimpleName(), reasonOfFailure.getMessage(), reasonOfFailure);
        }

        LOGGER.debug("Unexpected error during authentication for URI <{}>! Applying unauthorizedDirective",
                requestUri, reasonOfFailure);
        throw defaultUnauthorizedExceptionFactory.apply(dittoHeaders);
    }

}
