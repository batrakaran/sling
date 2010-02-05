/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.commons.auth.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import javax.jcr.LoginException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.commons.auth.AuthenticationSupport;
import org.apache.sling.commons.auth.Authenticator;
import org.apache.sling.commons.auth.NoAuthenticationHandlerException;
import org.apache.sling.commons.auth.impl.engine.EngineAuthenticationHandlerHolder;
import org.apache.sling.commons.auth.spi.AuthenticationFeedbackHandler;
import org.apache.sling.commons.auth.spi.AuthenticationHandler;
import org.apache.sling.commons.auth.spi.AuthenticationInfo;
import org.apache.sling.commons.auth.spi.DefaultAuthenticationFeedbackHandler;
import org.apache.sling.commons.osgi.OsgiUtil;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.api.TooManySessionsException;
import org.apache.sling.jcr.resource.JcrResourceResolverFactory;
import org.osgi.framework.AllServiceListener;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.http.HttpContext;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>SlingAuthenticator</code> class is the default implementation for
 * handling authentication. This class supports :
 * <ul>
 * <li>Support for login sessions where session ids are exchanged with cookies
 * <li>Support for multiple authentication handlers, which must implement the
 * {@link AuthenticationHandler} interface.
 * <li>
 * <p>
 * Currently this class does not support multiple handlers for any one request
 * URL.
 * <p>
 * Clients of this class use {@link #authenticate} method to create a
 * {@link AuthenticationInfo} for the handling of the request. This method uses
 * any of the handlers to extract the user information from the request. Next an
 * object is created for this user information. If no user information is
 * contained in the request (according to the handler), the anonymous info is
 * used.
 * <p>
 *
 * @scr.component name="org.apache.sling.engine.impl.auth.SlingAuthenticator"
 *                label="%auth.name" description="%auth.description"
 *                modified="modified" immediate="true"
 *
 * Register for three services
 * @scr.service interface="org.apache.sling.commons.auth.AuthenticationSupport"
 * @scr.service interface="org.apache.sling.commons.auth.Authenticator"
 * @scr.service interface="javax.servlet.ServletRequestListener"
 *
 * @scr.property name="service.vendor" value="The Apache Software Foundation"
 */
public class SlingAuthenticator implements Authenticator,
        AuthenticationSupport, ServletRequestListener {

    /** default log */
    private final Logger log = LoggerFactory.getLogger(SlingAuthenticator.class);

    /** @scr.property name="service.description" */
    private static final String DESCRIPTION = "Sling Request Authenticator";

    /**
     * @scr.property valueRef="DEFAULT_IMPERSONATION_COOKIE"
     */
    public static final String PAR_IMPERSONATION_COOKIE_NAME = "auth.sudo.cookie";

    /**
     * @scr.property valueRef="DEFAULT_IMPERSONATION_PARAMETER"
     */
    public static final String PAR_IMPERSONATION_PAR_NAME = "auth.sudo.parameter";

    /**
     * @scr.property valueRef="DEFAULT_ANONYMOUS_ALLOWED" type="Boolean"
     */
    public static final String PAR_ANONYMOUS_ALLOWED = "auth.annonymous";

    /**
     * @scr.property type="String" cardinality="+"
     */
    private static final String PAR_AUTH_REQ = "sling.auth.requirements";

    /** The default impersonation cookie name */
    private static final String DEFAULT_IMPERSONATION_COOKIE = "sling.sudo";

    /** The default impersonation parameter name */
    private static final String DEFAULT_IMPERSONATION_PARAMETER = "sudo";

    /** The default value for allowing anonymous access */
    private static final boolean DEFAULT_ANONYMOUS_ALLOWED = true;

    /**
     * The name of the session attribute which is set if the session created by
     * the {@link #handleSecurity(HttpServletRequest, HttpServletResponse)}
     * method is an impersonated session. The value of this attribute is the
     * name of the primary user authenticated with the credentials extracted
     * from the request using the authenitcation handler.
     */
    private static final String ATTR_IMPERSONATOR = "impersonator";

    /**
     * The name of the request attribute providing the authenticated JCR
     * Session. This is only provided for backwards compatibility and will be
     * removed in a future release.
     * <p>
     * <b>DO NOT USE ANY MORE</b>
     */
    private static final String REQUEST_ATTRIBUTE_SESSION = "javax.jcr.Session";

    /**
     * The name of the {@link AuthenticationInfo} property providing the option
     * {@link org.apache.sling.commons.auth.spi.AuthenticationFeedbackHandler}
     * handler to be called back on login failure or success.
     */
    private static final String AUTH_INFO_PROP_FEEDBACK_HANDLER = "$$sling.auth.AuthenticationFeedbackHandler$$";

    private static ArrayList<AbstractAuthenticationHandlerHolder> EMPTY_INFO = new ArrayList<AbstractAuthenticationHandlerHolder>();

    /** @scr.reference */
    private SlingRepository repository;

    /** @scr.reference */
    private JcrResourceResolverFactory resourceResolverFactory;

    private PathBasedHolderCache<AbstractAuthenticationHandlerHolder> authHandlerCache = new PathBasedHolderCache<AbstractAuthenticationHandlerHolder>();

    // package protected for access in inner class ...
    PathBasedHolderCache<AuthenticationRequirementHolder> authRequiredCache = new PathBasedHolderCache<AuthenticationRequirementHolder>();

    /** The name of the impersonation parameter */
    private String sudoParameterName;

    /** The name of the impersonation cookie */
    private String sudoCookieName;

    /** Cache control flag */
    private boolean cacheControl;

    /** Web Console Plugin service registration */
    private ServiceRegistration webConsolePlugin;

    /**
     * The listener for services registered with "sling.auth.requirements" to
     * update the internal authentication requirements
     */
    private SlingAuthenticatorServiceListener serviceListener;

    /**
     * ServiceTracker tracking AuthenticationHandler services
     */
    private ServiceTracker authHandlerTracker;

    /**
     * ServiceTracker tracking old Sling Engine AuthenticationHandler services
     */
    private ServiceTracker engineAuthHandlerTracker;

    // ---------- SCR integration

    @SuppressWarnings("unused")
    private void activate(final BundleContext bundleContext,
            final Map<String, Object> properties) {
        modified(properties);

        AuthenticatorWebConsolePlugin plugin = new AuthenticatorWebConsolePlugin(
            this);
        Hashtable<String, Object> props = new Hashtable<String, Object>();
        props.put("felix.webconsole.label", plugin.getLabel());
        props.put("felix.webconsole.title", plugin.getTitle());
        props.put("service.description",
            "Sling Request Authenticator WebConsole Plugin");
        props.put("service.vendor", properties.get("service.vendor"));

        webConsolePlugin = bundleContext.registerService(
            "javax.servlet.Servlet", plugin, props);

        serviceListener = SlingAuthenticatorServiceListener.createListener(
            bundleContext, this);

        authHandlerTracker = new AuthenticationHandlerTracker(bundleContext,
            authHandlerCache);
        engineAuthHandlerTracker = new EngineAuthenticationHandlerTracker(
            bundleContext, authHandlerCache);
    }

    private void modified(Map<String, Object> properties) {
        if (properties == null) {
            properties = new HashMap<String, Object>();
        }

        String newCookie = (String) properties.get(PAR_IMPERSONATION_COOKIE_NAME);
        if (newCookie == null || newCookie.length() == 0) {
            newCookie = DEFAULT_IMPERSONATION_COOKIE;
        }
        if (!newCookie.equals(this.sudoCookieName)) {
            log.info(
                "modified: Setting new cookie name for impersonation {} (was {})",
                newCookie, this.sudoCookieName);
            this.sudoCookieName = newCookie;
        }

        String newPar = (String) properties.get(PAR_IMPERSONATION_PAR_NAME);
        if (newPar == null || newPar.length() == 0) {
            newPar = DEFAULT_IMPERSONATION_PARAMETER;
        }
        if (!newPar.equals(this.sudoParameterName)) {
            log.info(
                "modified: Setting new parameter name for impersonation {} (was {})",
                newPar, this.sudoParameterName);
            this.sudoParameterName = newPar;
        }

        authRequiredCache.clear();

        boolean flag = OsgiUtil.toBoolean(
            properties.get(PAR_ANONYMOUS_ALLOWED), DEFAULT_ANONYMOUS_ALLOWED);
        authRequiredCache.addHolder(new AuthenticationRequirementHolder("/",
            !flag, DESCRIPTION));

        String[] authReqs = OsgiUtil.toStringArray(properties.get(PAR_AUTH_REQ));
        if (authReqs != null) {
            for (String authReq : authReqs) {
                if (authReq != null && authReq.length() > 0) {
                    authRequiredCache.addHolder(AuthenticationRequirementHolder.fromConfig(
                        authReq, DESCRIPTION));
                }
            }
        }

        // don't require authentication for login/logout servlets
        authRequiredCache.addHolder(new AuthenticationRequirementHolder(
            LoginServlet.SERVLET_PATH, false, DESCRIPTION));
        authRequiredCache.addHolder(new AuthenticationRequirementHolder(
            LogoutServlet.SERVLET_PATH, false, DESCRIPTION));

        // add all registered services
        if (serviceListener != null) {
            serviceListener.registerServices();
        }
    }

    @SuppressWarnings("unused")
    private void deactivate(final BundleContext bundleContext) {
        if (engineAuthHandlerTracker != null) {
            engineAuthHandlerTracker.close();
            engineAuthHandlerTracker = null;
        }

        if (authHandlerTracker != null) {
            authHandlerTracker.close();
            authHandlerTracker = null;
        }

        if (serviceListener != null) {
            bundleContext.removeServiceListener(serviceListener);
            serviceListener = null;
        }

        if (webConsolePlugin != null) {
            webConsolePlugin.unregister();
            webConsolePlugin = null;
        }
    }

    // --------- AuthenticationSupport interface

    /**
     * Checks the authentication contained in the request. This check is only
     * based on the original request object, no URI translation has taken place
     * yet.
     * <p>
     *
     * @param request The request object containing the information for the
     *            authentication.
     * @param response The response object which may be used to send the information
     *            on the request failure to the user.
     *
     * @return <code>true</code> if request processing should continue assuming
     *      successfull authentication. If <code>false</code> is returned it
     *      is assumed a response has been sent to the client and the request
     *      is terminated.
     */
    public boolean handleSecurity(HttpServletRequest request,
            HttpServletResponse response) {

        // 0. Nothing to do, if the session is also in the request
        // this might be the case if the request is handled as a result
        // of a servlet container include inside another Sling request
        Object sessionAttr = request.getAttribute(REQUEST_ATTRIBUTE_RESOLVER);
        if (sessionAttr instanceof ResourceResolver) {
            log.debug("handleSecurity: Request already authenticated, nothing to do");
            return true;
        } else if (sessionAttr != null) {
            // warn and remove existing non-session
            log.warn(
                "handleSecurity: Overwriting existing ResourceResolver attribute ({})",
                sessionAttr);
            request.removeAttribute(REQUEST_ATTRIBUTE_RESOLVER);
        }

        // 1. Ask all authentication handlers to try to extract credentials
        AuthenticationInfo authInfo = getAuthenticationInfo(request, response);

        // 3. Check Credentials
        if (authInfo == AuthenticationInfo.DOING_AUTH) {

            log.debug("handleSecurity: ongoing authentication in the handler");
            return false;

        } else if (authInfo == null) {

            log.debug("handleSecurity: No credentials in the request, anonymous");
            return getAnonymousSession(request, response);

        } else {

            log.debug("handleSecurity: Trying to get a session for {}",
                authInfo.getUser());
            return getSession(request, response, authInfo);

        }
    }

    // ---------- Authenticator interface

    /**
     * Requests authentication information from the client. Returns
     * <code>true</code> if the information has been requested and request
     * processing can be terminated. Otherwise the request information could not
     * be requested and the request should be terminated with a 403/FORBIDDEN
     * response.
     * <p>
     * Any response sent by the handler is also handled by the error handler
     * infrastructure.
     *
     * @param request The request object
     * @param response The response object to which to send the request
     * @throws IllegalStateException If response is already committed
     * @throws NoAuthenticationHandlerException If no authentication handler
     *             claims responsibility to authenticate the request.
     */
    public void login(HttpServletRequest request, HttpServletResponse response) {

        // ensure the response is not committed yet
        if (response.isCommitted()) {
            throw new IllegalStateException("Response already committed");
        }

        // select path used for authentication handler selection
        final ArrayList<AbstractAuthenticationHandlerHolder> holderList = findApplicableAuthenticationHandlers(request);
        final String path = getHandlerSelectionPath(request);
        boolean done = false;
        for (int i = 0; !done && i < holderList.size(); i++) {
            final AbstractAuthenticationHandlerHolder holder = holderList.get(i);
            if (path.startsWith(holder.path)) {
                log.debug("login: requesting authentication using handler: {}",
                    holder);

                try {
                    done = holder.requestCredentials(request, response);
                } catch (IOException ioe) {
                    log.error(
                        "login: Failed sending authentication request through handler "
                            + holder + ", access forbidden", ioe);
                    done = true;
                }
            }
        }

        // no handler could send an authentication request, throw
        if (!done) {
            log.info("login: No handler for request ({} handlers available)",
                holderList.size());
            throw new NoAuthenticationHandlerException();
        }
    }

    /**
     * Logs out the user calling all applicable
     * {@link org.apache.sling.commons.auth.spi.AuthenticationHandler}
     * authentication handlers.
     *
     */
    public void logout(HttpServletRequest request, HttpServletResponse response) {

        // ensure the response is not committed yet
        if (response.isCommitted()) {
            throw new IllegalStateException("Response already committed");
        }

        final ArrayList<AbstractAuthenticationHandlerHolder> holderList = findApplicableAuthenticationHandlers(request);
        final String path = getHandlerSelectionPath(request);
        for (int i = 0; i < holderList.size(); i++) {
            AbstractAuthenticationHandlerHolder holder = holderList.get(i);
            if (path.startsWith(holder.path)) {
                log.debug("logout: dropping authentication using handler: {}",
                    holder);

                try {
                    holder.dropCredentials(request, response);
                } catch (IOException ioe) {
                    log.error(
                        "logout: Failed dropping authentication through handler "
                            + holder, ioe);
                }
            }
        }
    }

    // ---------- ServletRequestListener

    public void requestInitialized(ServletRequestEvent sre) {
        // don't care
    }

    public void requestDestroyed(ServletRequestEvent sre) {
        ServletRequest request = sre.getServletRequest();
        Object sessionAttr = request.getAttribute(SlingAuthenticatorSession.ATTR_NAME);
        if (sessionAttr instanceof SlingAuthenticatorSession) {
            ((SlingAuthenticatorSession) sessionAttr).logout();

            request.removeAttribute(REQUEST_ATTRIBUTE_RESOLVER);
            request.removeAttribute(REQUEST_ATTRIBUTE_SESSION);
            request.removeAttribute(SlingAuthenticatorSession.ATTR_NAME);
        }
    }

    // ---------- WebConsolePlugin support

    ArrayList<AbstractAuthenticationHandlerHolder> getAuthenticationHandler() {
        return authHandlerCache.getHolders();
    }

    ArrayList<AuthenticationRequirementHolder> getAuthenticationRequirements() {
        return authRequiredCache.getHolders();
    }

    // ---------- internal

    private ArrayList<AbstractAuthenticationHandlerHolder> findApplicableAuthenticationHandlers(
            HttpServletRequest request) {

        final ArrayList<AbstractAuthenticationHandlerHolder> infos = authHandlerCache.findApplicableHolder(request);
        if (infos != null) {
            return infos;
        }

        return EMPTY_INFO;
    }

    private AuthenticationInfo getAuthenticationInfo(
            HttpServletRequest request, HttpServletResponse response) {

        // Get the path used to select the authenticator, if the SlingServlet
        // itself has been requested without any more info, this will be null
        // and we assume the root (SLING-722)
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.length() == 0) {
            pathInfo = "/";
        }

        ArrayList<AbstractAuthenticationHandlerHolder> local = findApplicableAuthenticationHandlers(request);
        for (int i = 0; i < local.size(); i++) {
            AbstractAuthenticationHandlerHolder holder = local.get(i);
            if (pathInfo.startsWith(holder.path)) {
                final AuthenticationInfo authInfo = holder.extractCredentials(
                    request, response);

                if (authInfo != null) {
                    // add the feedback handler to the info (may be null)
                    authInfo.put(AUTH_INFO_PROP_FEEDBACK_HANDLER,
                        holder.getFeedbackHandler());

                    return authInfo;
                }
            }
        }

        // no handler found for the request ....
        log.debug("getAuthenticationInfo: no handler could extract credentials");
        return null;
    }

    /**
     * Try to acquire an Session as indicated by authInfo
     *
     * @return <code>true</code> if request processing should continue assuming
     *         successfull authentication. If <code>false</code> is returned it
     *         is assumed a response has been sent to the client and the request
     *         is terminated.
     */
    private boolean getSession(final HttpServletRequest request,
            final HttpServletResponse response, final AuthenticationInfo authInfo) {

        // prepare the feedback handler
        final AuthenticationFeedbackHandler feedbackHandler = (AuthenticationFeedbackHandler) authInfo.remove(AUTH_INFO_PROP_FEEDBACK_HANDLER);

        // try to connect
        try {
            Session session = repository.login(authInfo.getCredentials(),
                authInfo.getWorkspaceName());

            // handle impersonation
            session = handleImpersonation(request, response, session);

            // handle success feedback
            if (feedbackHandler != null) {

                // call the feedback handler, terminating the request if
                // so desired by the handler
                if (feedbackHandler.authenticationSucceeded(request, response,
                    authInfo)) {
                    return false;
                }

            } else {

                // if there is no feedback handler: check whether the client
                // asked for redirect after authentication and/or impersonation
                if (DefaultAuthenticationFeedbackHandler.handleRedirect(
                    request, response)) {
                    return false;
                }

            }

            // no redirect desired, so continue processing by first setting
            // the request attributes and then returning true
            setAttributes(session, authInfo.getAuthType(), request);
            return true;

        } catch (RepositoryException re) {

            // handle failure feedback before proceeding to handling the
            // failed login internally
            if (feedbackHandler != null) {
                feedbackHandler.authenticationFailed(request, response, authInfo);
            }

            // now find a way to get credentials
            handleLoginFailure(request, response, authInfo.getUser(), re);

        }

        // end request
        return false;

    }

    /** Try to acquire an anonymous Session */
    private boolean getAnonymousSession(final HttpServletRequest request,
            final HttpServletResponse response) {

        // Get an anonymous session if allowed, or if we are handling
        // a request for the login servlet
        if (isAnonAllowed(request)) {

            try {

                Session session = repository.login();

                // check whether the client asked for redirect after
                // authentication and/or impersonation
                if (DefaultAuthenticationFeedbackHandler.handleRedirect(
                    request, response)) {
                    return false;
                }

                // set the attributes for further processing
                setAttributes(session, null, request);

                return true;

            } catch (RepositoryException re) {

                // cannot login > fail login, do not try to authenticate
                handleLoginFailure(request, response, "anonymous user", re);
                return false;

            }
        }

        // If we get here, anonymous access is not allowed: redirect
        // to the login servlet
        log.info("getAnonymousSession: Anonymous access not allowed by configuration - requesting credentials");
        doLogin(request, response);

        // fallback to no session
        return false;
    }

    private boolean isAnonAllowed(HttpServletRequest request) {

        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.length() == 0) {
            pathInfo = "/";
        }

        ArrayList<AuthenticationRequirementHolder> holderList = authRequiredCache.findApplicableHolder(request);
        if (holderList != null && !holderList.isEmpty()) {
            for (int i = 0; i < holderList.size(); i++) {
                AuthenticationRequirementHolder holder = holderList.get(i);
                if (pathInfo.startsWith(holder.path)) {
                    return !holder.requiresAuthentication();
                }
            }
        }

        if (LoginServlet.SERVLET_PATH.equals(pathInfo)) {
            return true;
        }

        // fallback to anonymous not allowed (aka authentication required)
        return false;
    }

    private void handleLoginFailure(final HttpServletRequest request,
            final HttpServletResponse response, final String user,
            final Exception reason) {

        if (reason instanceof TooManySessionsException) {

            // to many users, send a 503 Service Unavailable
            log.info("handleLoginFailure: Too many sessions for {}: {}", user,
                reason.getMessage());

            try {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "SlingAuthenticator: Too Many Users");
            } catch (IOException ioe) {
                log.error("handleLoginFailure: Cannot send status 503 to client", ioe);
            }

        } else if (reason instanceof LoginException) {

            // request authentication information and send 403 (Forbidden)
            // if no handler can request authentication information.
            log.info("handleLoginFailure: Unable to authenticate {}: {}", user,
                reason.getMessage());
            log.debug("handleLoginFailure", reason);

            doLogin(request, response);

        } else {

            // general problem, send a 500 Internal Server Error
            log.error("handleLoginFailure: Unable to authenticate " + user, reason);

            try {
                response.sendError(
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "SlingAuthenticator: data access error, reason="
                        + reason.getClass().getSimpleName());
            } catch (IOException ioe) {
                log.error("handleLoginFailure: Cannot send status 500 to client", ioe);
            }
        }

    }

    /**
     * Calls the {@link #login(HttpServletRequest, HttpServletResponse)} method
     * catching declared exceptions of that method and cleanly handling and
     * logging them. Particularly if no authentication handler is available to
     * request credentials a 403/FORBIDDEN response is sent back to the client.
     */
    private void doLogin(HttpServletRequest request,
            HttpServletResponse response) {

        try {

            login(request, response);

        } catch (IllegalStateException ise) {

            log.error("doLogin: Cannot login: Response already committed");

        } catch (NoAuthenticationHandlerException nahe) {

            log.error("doLogin: Cannot login: No AuthenticationHandler available to handle the request");

            try {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Cannot login");
            } catch (IOException ioe) {
                log.error("doLogin: Failed sending 403 status", ioe);
            }

        }
    }

    /**
     * Sets the request attributes required by the OSGi HttpContext interface
     * specification for the <code>handleSecurity</code> method. In addition the
     * {@link SlingHttpContext#SESSION} request attribute is set with the JCR
     * Session.
     */
    private void setAttributes(final Session session, final String authType,
            final HttpServletRequest request) {

        final ResourceResolver resolver = resourceResolverFactory.getResourceResolver(session);
        final SlingAuthenticatorSession sas = new SlingAuthenticatorSession(
            session);

        // HttpService API required attributes
        request.setAttribute(HttpContext.REMOTE_USER, session.getUserID());
        request.setAttribute(HttpContext.AUTHENTICATION_TYPE, authType);

        // resource resolver for down-stream use
        request.setAttribute(REQUEST_ATTRIBUTE_RESOLVER, resolver);
        request.setAttribute(SlingAuthenticatorSession.ATTR_NAME, sas);

        // JCR session for backwards compatibility
        request.setAttribute(REQUEST_ATTRIBUTE_SESSION, session);

        log.debug(
            "setAttributes: ResourceResolver stored as request attribute: user={}, workspace={}",
            session.getUserID(), session.getWorkspace().getName());
    }

    /**
     * Sends the session cookie for the name session with the given age in
     * seconds. This sends a Version 1 cookie.
     *
     * @param response The {@link DeliveryHttpServletResponse} on which to send
     *            back the cookie.
     * @param user The name of the user to impersonate as. This will be quoted
     *            and used as the cookie value.
     * @param maxAge The maximum age of the cookie in seconds. Positive values
     *            are persisted on the browser for the indicated number of
     *            seconds, setting the age to 0 (zero) causes the cookie to be
     *            deleted in the browser and using a negative value defines a
     *            temporary cookie to be deleted when the browser exits.
     * @param path The cookie path to use. This is intended to be the web
     *            application's context path. If this is empty or
     *            <code>null</code> the root path will be used assuming the web
     *            application is registered at the root context.
     * @param owner The name of the user originally authenticated in the request
     *            and who is now impersonating as <i>user</i>.
     */
    private void sendSudoCookie(HttpServletResponse response,
            final String user, final int maxAge, final String path,
            final String owner) {

        final String quotedUser;
        try {
            quotedUser = quoteCookieValue(user);
        } catch (IllegalArgumentException iae) {
            log.error(
                "sendSudoCookie: Failed to quote value '{}' of cookie {}: {}",
                new Object[] { user, this.sudoCookieName, iae.getMessage() });
            return;
        }

        if (quotedUser != null) {
            Cookie cookie = new Cookie(this.sudoCookieName, quotedUser);
            cookie.setMaxAge(maxAge);
            cookie.setPath((path == null || path.length() == 0) ? "/" : path);
            try {
                cookie.setComment(owner + " impersonates as " + user);
            } catch (IllegalArgumentException iae) {
                // ignore
            }

            response.addCookie(cookie);

            // Tell a potential proxy server that this request is uncacheable
            // due to the Set-Cookie header being sent
            if (this.cacheControl) {
                response.addHeader("Cache-Control", "no-cache=\"Set-Cookie\"");
            }
        }
    }

    /**
     * Handles impersonation based on the request parameter for impersonation
     * (see {@link #sudoParameterName}) and the current setting in the sudo
     * cookie.
     * <p>
     * If the sudo parameter is empty or missing, the current cookie setting for
     * impersonation is used. Else if the parameter is <code>-</code>, the
     * current cookie impersonation is removed and no impersonation will take
     * place for this request. Else the parameter is assumed to contain the
     * handle of a user page acceptable for the {@link Session#impersonate}
     * method.
     *
     * @param req The {@link DeliveryHttpServletRequest} optionally containing
     *            the sudo parameter.
     * @param res The {@link DeliveryHttpServletResponse} to send the
     *            impersonation cookie.
     * @param session The real {@link Session} to optionally replace with an
     *            impersonated session.
     * @return The impersonated session or the input session.
     * @see Session#impersonate for details on the user configuration
     *      requirements for impersonation.
     */
    private Session handleImpersonation(HttpServletRequest req,
            HttpServletResponse res, Session session) {

        // the current state of impersonation
        String currentSudo = null;
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (int i = 0; currentSudo == null && i < cookies.length; i++) {
                if (sudoCookieName.equals(cookies[i].getName())) {
                    currentSudo = unquoteCookieValue(cookies[i].getValue());
                }
            }
        }

        /**
         * sudo parameter : empty or missing to continue to use the setting
         * already stored in the session; or "-" to remove impersonationa
         * altogether (also from the session); or the handle of a user page to
         * impersonate as that user (if possible)
         */
        String sudo = req.getParameter(this.sudoParameterName);
        if (sudo == null || sudo.length() == 0) {
            sudo = currentSudo;
        } else if ("-".equals(sudo)) {
            sudo = null;
        }

        // sudo the session if needed
        final String authUser = session.getUserID();
        if (sudo != null && sudo.length() > 0) {
            try {
                // impersonate setting the respective attribute
                final SimpleCredentials creds = new SimpleCredentials(sudo,
                    new char[0]);
                creds.setAttribute(ATTR_IMPERSONATOR, authUser);
                final Session impersonated = session.impersonate(creds);

                // logout the original session and replace with impersonated
                // session.
                session.logout();
                session = impersonated;

            } catch (RepositoryException re) {

                // log an error message if impersonation fails
                log.error("handleImpersonation: Failed to impersonate "
                    + authUser + " as " + sudo + ", processing request as "
                    + authUser, re);

                // clear sudo to revert impersonation
                sudo = null;
            }
        }
        // invariant: same session or successful impersonation

        // set the (new) impersonation
        if (sudo != currentSudo) {
            if (sudo == null) {
                // Parameter set to "-" to clear impersonation, which was
                // active due to cookie setting

                // clear impersonation
                this.sendSudoCookie(res, "", 0, req.getContextPath(), authUser);

            } else if (currentSudo == null || !currentSudo.equals(sudo)) {
                // Parameter set to a name. As the cookie is not set yet
                // or is set to another name, send the cookie with current sudo

                // (re-)set impersonation
                this.sendSudoCookie(res, sudo, -1, req.getContextPath(), authUser);
            }
        }

        // return the session
        return session;
    }

    /**
     * Returns the path to be used to select the authentication handler to login
     * or logout with.
     * <p>
     * This method uses the {@link Authenticator#LOGIN_RESOURCE} request
     * attribute. If this attribute is not set (or is not a string), the request
     * path info is used. If this is not set either, or is the empty string, "/"
     * is returned.
     *
     * @param request The request providing the request attribute or path info.
     * @return The path as set by the request attribute or the path info or "/"
     *         if neither is set.
     */
    private String getHandlerSelectionPath(HttpServletRequest request) {
        final Object loginPathO = request.getAttribute(Authenticator.LOGIN_RESOURCE);
        String path = (loginPathO instanceof String)
                ? (String) loginPathO
                : request.getPathInfo();
        if (path == null || path.length() == 0) {
            path = "/";
        }
        return path;
    }

    /**
     * Ensures the cookie value is properly quoted for transmission to the
     * client.
     * <p>
     * The problem is, that the character set of cookie values is limited by
     * RFC 2109 defining that a cookie value must be token or quoted-string
     * according to RFC-2616:
     * <pre>
     * token = 1*<any CHAR except CTLs or separators>
     * separators = "(" | ")" | "<" | ">" | "@"
     *                | "," | ";" | ":" | "\" | <">
     *                | "/" | "[" | "]" | "?" | "="
     *                | "{" | "}" | SP | HT
     *
     * quoted-string = ( <"> *(qdtext | quoted-pair ) <"> )
     * qdtext = <any TEXT except <">>
     * quoted-pair = "\" CHAR
     *
     * @param value The cookie value to quote
     * @return The quoted cookie value
     * @throws IllegalArgumentException If the cookie value is <code>null</code>
     *             or cannot be quoted, primarily because it contains a quote
     *             sign.
     */
    static String quoteCookieValue(final String value) {
        // method is package private to enable unit testing

        if (value == null) {
            throw new IllegalArgumentException("Cookie value may not be null");
        }

        StringBuilder builder = new StringBuilder(value.length() * 2);
        builder.append('"');
        for (int i=0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                builder.append("\\\"");
            } else if (c == 127 || (c < 32 && c != '\t')) {
                throw new IllegalArgumentException(
                    "Cookie value may not contain CTL character");
            } else {
                builder.append(c);
            }
        }
        builder.append('"');

        return builder.toString();
    }

    /**
     * Removes (optional) quotes from a cookie value to get the raw value of
     * the cookie.
     *
     * @param value The cookie value to unquote
     * @return The unquoted cookie value
     */
    static String unquoteCookieValue(final String value) {
        // method is package private to enable unit testing

        // return value unmodified if null, empty or not starting with a quote
        if (value == null || value.length() == 0 || value.charAt(0) != '"') {
            return value;
        }

        StringBuilder builder = new StringBuilder(value.length());
        for (int i=1; i < value.length()-1; i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                builder.append(c);
            }
        }

        return builder.toString();
    }

    private static class SlingAuthenticatorSession {

        static final String ATTR_NAME = "$$org.apache.sling.commons.auth.impl.SlingAuthenticatorSession$$";

        private Session session;

        SlingAuthenticatorSession(final Session session) {
            this.session = session;
        }

        void logout() {
            if (session != null) {
                try {
                    // logout if session is still alive (and not logged out)
                    if (session.isLive()) {
                        session.logout();
                    }
                } catch (Throwable t) {
                    // TODO: might log
                } finally {
                    session = null;
                }
            }
        }

        @Override
        protected void finalize() {
            logout();
        }
    }

    private static class SlingAuthenticatorServiceListener implements
            AllServiceListener {

        private final SlingAuthenticator authenticator;

        private final HashMap<Object, AuthenticationRequirementHolder[]> props = new HashMap<Object, AuthenticationRequirementHolder[]>();

        static SlingAuthenticatorServiceListener createListener(
                final BundleContext context,
                final SlingAuthenticator authenticator) {
            SlingAuthenticatorServiceListener listener = new SlingAuthenticatorServiceListener(
                authenticator);
            try {
                final String filter = "(" + PAR_AUTH_REQ + "=*)";
                context.addServiceListener(listener, filter);
                ServiceReference[] refs = context.getAllServiceReferences(null,
                    filter);
                if (refs != null) {
                    for (ServiceReference ref : refs) {
                        listener.addService(ref);
                    }
                }
                return listener;
            } catch (InvalidSyntaxException ise) {
            }
            return null;
        }

        private SlingAuthenticatorServiceListener(
                final SlingAuthenticator authenticator) {
            this.authenticator = authenticator;
        }

        public void serviceChanged(ServiceEvent event) {

            // modification of service properties, unregistration of the
            // service or service properties does not contain requirements
            // property any longer (new event with type 8 added in OSGi Core
            // 4.2)
            if ((event.getType() & (ServiceEvent.MODIFIED
                | ServiceEvent.UNREGISTERING | 8)) != 0) {
                removeService(event.getServiceReference());
            }

            // add requirements for newly registered services and for
            // updated services
            if ((event.getType() & (ServiceEvent.REGISTERED | ServiceEvent.MODIFIED)) != 0) {
                addService(event.getServiceReference());
            }
        }

        void registerServices() {
            AuthenticationRequirementHolder[][] authReqsList;
            synchronized (props) {
                authReqsList = props.values().toArray(
                    new AuthenticationRequirementHolder[props.size()][]);
            }

            for (AuthenticationRequirementHolder[] authReqs : authReqsList) {
                registerService(authReqs);
            }
        }

        private void registerService(
                final AuthenticationRequirementHolder[] authReqs) {
            for (AuthenticationRequirementHolder authReq : authReqs) {
                authenticator.authRequiredCache.addHolder(authReq);
            }
        }

        private void addService(final ServiceReference ref) {
            final String[] authReqPaths = OsgiUtil.toStringArray(ref.getProperty(PAR_AUTH_REQ));
            final String source = getSource(ref);

            ArrayList<AuthenticationRequirementHolder> authReqList = new ArrayList<AuthenticationRequirementHolder>();
            for (String authReq : authReqPaths) {
                if (authReq != null && authReq.length() > 0) {
                    authReqList.add(AuthenticationRequirementHolder.fromConfig(
                        authReq, source));
                }
            }

            final AuthenticationRequirementHolder[] authReqs = authReqList.toArray(new AuthenticationRequirementHolder[authReqList.size()]);
            registerService(authReqs);

            synchronized (props) {
                props.put(ref.getProperty(Constants.SERVICE_ID), authReqs);
            }
        }

        private void removeService(final ServiceReference ref) {
            final AuthenticationRequirementHolder[] authReqs;
            synchronized (props) {
                authReqs = props.remove(ref.getProperty(Constants.SERVICE_ID));
            }

            if (authReqs != null) {
                for (AuthenticationRequirementHolder authReq : authReqs) {
                    authenticator.authRequiredCache.removeHolder(authReq);
                }
            }
        }

        private String getSource(final ServiceReference ref) {
            final String descr = OsgiUtil.toString(
                ref.getProperty(Constants.SERVICE_DESCRIPTION), null);
            if (descr != null) {
                return descr;
            }

            return "Service "
                + OsgiUtil.toString(ref.getProperty(Constants.SERVICE_ID),
                    "unknown");
        }
    }

    private static class AuthenticationHandlerTracker extends ServiceTracker {

        private final PathBasedHolderCache<AbstractAuthenticationHandlerHolder> authHandlerCache;

        private final HashMap<Object, AbstractAuthenticationHandlerHolder[]> handlerMap = new HashMap<Object, AbstractAuthenticationHandlerHolder[]>();

        AuthenticationHandlerTracker(
                final BundleContext context,
                final PathBasedHolderCache<AbstractAuthenticationHandlerHolder> authHandlerCache) {
            this(context, AuthenticationHandler.SERVICE_NAME, authHandlerCache);
        }

        protected AuthenticationHandlerTracker(
                final BundleContext context,
                final String className,
                final PathBasedHolderCache<AbstractAuthenticationHandlerHolder> authHandlerCache) {
            super(context, className, null);
            this.authHandlerCache = authHandlerCache;

            open();
        }

        @Override
        public Object addingService(ServiceReference reference) {
            final Object service = super.addingService(reference);
            bindAuthHandler(service, reference);
            return service;
        }

        @Override
        public void modifiedService(ServiceReference reference, Object service) {
            unbindAuthHandler(reference);
            bindAuthHandler(service, reference);
        }

        @Override
        public void removedService(ServiceReference reference, Object service) {
            unbindAuthHandler(reference);
            super.removedService(reference, service);
        }

        protected AbstractAuthenticationHandlerHolder createHolder(
                final String path, final Object handler) {
            return new AuthenticationHandlerHolder(path,
                (AuthenticationHandler) handler);
        }

        private void bindAuthHandler(final Object handler, ServiceReference ref) {
            final String paths[] = OsgiUtil.toStringArray(ref.getProperty(AuthenticationHandler.PATH_PROPERTY));
            if (paths != null && paths.length > 0) {

                // generate the holders
                ArrayList<AbstractAuthenticationHandlerHolder> holderList = new ArrayList<AbstractAuthenticationHandlerHolder>();
                for (String path : paths) {
                    if (path != null && path.length() > 0) {
                        holderList.add(createHolder(path, handler));
                    }
                }

                // register the hodlers
                AbstractAuthenticationHandlerHolder[] holders = holderList.toArray(new AbstractAuthenticationHandlerHolder[holderList.size()]);
                for (AbstractAuthenticationHandlerHolder holder : holders) {
                    authHandlerCache.addHolder(holder);
                }

                // keep a copy of them for unregistration later
                synchronized (handler) {
                    handlerMap.put(ref.getProperty(Constants.SERVICE_ID),
                        holders);
                }
            }
        }

        private void unbindAuthHandler(ServiceReference ref) {
            final AbstractAuthenticationHandlerHolder[] holders;
            synchronized (handlerMap) {
                holders = handlerMap.remove(ref.getProperty(Constants.SERVICE_ID));
            }

            if (holders != null) {
                for (AbstractAuthenticationHandlerHolder holder : holders) {
                    authHandlerCache.removeHolder(holder);
                }
            }
        }
    }

    private static class EngineAuthenticationHandlerTracker extends
            AuthenticationHandlerTracker {

        EngineAuthenticationHandlerTracker(
                final BundleContext context,
                final PathBasedHolderCache<AbstractAuthenticationHandlerHolder> authHandlerCache) {
            super(context,
                "org.apache.sling.engine.auth.AuthenticationHandler",
                authHandlerCache);
        }

        @SuppressWarnings("deprecation")
        @Override
        protected AbstractAuthenticationHandlerHolder createHolder(String path,
                Object handler) {
            return new EngineAuthenticationHandlerHolder(path,
                (org.apache.sling.engine.auth.AuthenticationHandler) handler);

        }
    }

}
