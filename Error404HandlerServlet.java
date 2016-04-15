package de.goldbachinteractive.gbi.redirecthandler.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.Logger;

/**
 * This is a sample implementation, of how to integrate the gbi-redirecthandler
 * in a HTTP servlet container. <br>
 * <br>
 * You may configure your web.xml as follows, to integrate this servlet.
 * 
 * <br>
 * <b>parameters:</b> <br>
 * <b>xGbiKey</b> license key for api access<br>
 * <b>redirectProcessorUrls</b> comma separated list of redirect processor urls<br>
 * <b>timeout</b> redirect request timeout in ms<br>
 * <b>default404Page</b> the 404 page if no redirect available<br>
 * <br>
 * <b>sample:</b>
 * 
 * <pre>
 * {@code
 * <servlet>
 * 	<servlet-name>ErrorHandler</servlet-name>
 * 	<servlet-class>de.goldbachinteractive.gbi.redirecthandler.client.Error404HandlerServlet</servlet-class>
 * 	<init-param>
 * 		<param-name>xGbiKey</param-name>
 * 		<param-value>38e34544bf26e1b5cf91f3b1d4589a18</param-value>
 * 	</init-param>
 * 	<init-param>
 * 		<param-name>redirectProcessorUrls</param-name>
 * 		<param-value>https://redirectProcessor1.local/redirecthandler/,http://redirectProcessor2.local/redirecthandler</param-value>
 * 	</init-param>
 * 	<init-param>
 * 		<param-name>timeout</param-name>
 * 		<param-value>2000</param-value>
 * 	</init-param>
 * 	<init-param>
 * 		<param-name>default404Page</param-name>
 * 		<param-value>/DefaultErrorHandler</param-value>
 * 	</init-param>
 * </servlet>
 * <servlet-mapping>
 * 	<servlet-name>ErrorHandler</servlet-name>
 * 	<url-pattern>/ErrorHandler</url-pattern>
 * </servlet-mapping>
 * <error-page>
 * 	<error-code>404</error-code>
 * 	<location>/ErrorHandler</location>
 * </error-page>
 * }
 * </pre>
 * 
 * <br>
 * <b>tested with the following dependencies:</b> <br>
 * apache commons-lang 2.6</b> <br>
 * apache commons-validator 1.3.1</b> <br>
 * apache httpclient 4.3.1<b></b> <br>
 * <b>log4h 1.2.14</b>
 * 
 * @author hao.jin, kai.faulstich
 * 
 */
public class Error404HandlerServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = Logger.getLogger(Error404HandlerServlet.class);

	private static final String GBI_KEY_HEADER = "X-gbi-key";

	private static final String INIT_PARAMETER_XGBIKEY = "xGbiKey";

	private static final String INIT_PARAMETER_TIMEOUT = "timeout";

	private static final String INIT_PARAMETER_DEFAULT_404_PAGE = "default404Page";

	private static final String INIT_PARAMETER_REDIRECT_PROCESSOR_URLS = "redirectProcessorUrls";

	private static final String ENCODING_UTF8 = "UTF-8";

	private List<String> redirectProcessorUrls;
	private String default404Page = null;
	private String xGbiKey;

	// preset the timeout with a minimum of 1000ms
	private int timeout = 1000;

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);

		// the xGbiKey parameter must be set for the redirecthandler to find its
		// rules mapping
		xGbiKey = getInitParameter(INIT_PARAMETER_XGBIKEY);
		if (StringUtils.isBlank(xGbiKey)) {
			throw new ServletException("xGbiKey must be set");
		}

		// you should configure a timeout more than the minimum of 1000ms
		try {
			int configuredTimeOut = Integer.parseInt(getInitParameter(INIT_PARAMETER_TIMEOUT));
			if (configuredTimeOut < timeout) {
				logger.warn(String.format("configured timeout (%d) must be more than minimum of %d", configuredTimeOut, timeout));
			}
			else {
				timeout = configuredTimeOut;
			}
		}
		catch (NumberFormatException e) {
			logger.warn(String.format("invalid parameter %s. Used minimum timeout of %dms", INIT_PARAMETER_TIMEOUT, timeout));
		}

		// default 404 page is optional
		default404Page = getInitParameter(INIT_PARAMETER_DEFAULT_404_PAGE);
		if (StringUtils.isBlank(default404Page)) {
			logger.warn("no default 404 page set");
		}

		String redirectProcessorUrlsConfigParameter = getInitParameter(INIT_PARAMETER_REDIRECT_PROCESSOR_URLS);
		if (StringUtils.isBlank(redirectProcessorUrlsConfigParameter)) {
			throw new ServletException(String.format("%s must be set", INIT_PARAMETER_REDIRECT_PROCESSOR_URLS));
		}
		// split config parameter value to a list and convert
		// redirectProcessorUrls to lower case
		redirectProcessorUrls = getUrlList(redirectProcessorUrlsConfigParameter, ",");
		if (redirectProcessorUrls.isEmpty()) {
			throw new ServletException(String.format("%s must be set", INIT_PARAMETER_REDIRECT_PROCESSOR_URLS));
		}
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		redirect(request, response);
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}

	/**
	 * Requests the configured redirect processors randomly for redirect until a
	 * redirect found. Uses the default 404 page if no redirect available.
	 * 
	 * @param request
	 *            The original request.
	 * @param response
	 *            The processed response.
	 * @throws UnsupportedEncodingException
	 *             Thrown, if encoding the request URL to UTF-8 failed.
	 */
	public void redirect(HttpServletRequest request, HttpServletResponse response) throws UnsupportedEncodingException {

		CloseableHttpClient httpClient = HttpClients.createDefault();
		String path = (String) request.getAttribute("javax.servlet.error.request_uri");
		String host = request.getHeader("host");
		String scheme = request.getScheme();
		// to use absolute url with scheme
		String requestUri = scheme + "://" + host + path;
		// the url must be encoded
		requestUri = URLEncoder.encode(requestUri, ENCODING_UTF8);

		// copy redirect dispatcher URLs, because we manipulate this list for
		// random access
		List<String> dispatcherUrls = new ArrayList<String>(redirectProcessorUrls);
		int urlCount = dispatcherUrls.size();
		while (urlCount > 0) {
			// use a random redirect processor
			Random random = new Random();
			int position = random.nextInt(urlCount);
			String processorUrl = dispatcherUrls.remove(position);

			// build the request url to the redirect processor
			String redirectRequestUrl = String.format("%s?r=%s", processorUrl, requestUri);

			// ask for redirect, returns with true on redirected, and false if
			// no redirect found
			if (redirectRequest(redirectRequestUrl, httpClient, request, response, timeout)) {
				logger.info("redirected");
				return;
			}
			urlCount--;
		}
		// we did not found a redirect, so use the default 404 page, configured
		redirectDefault404Page(request, response);
	}

	/**
	 * Requests a redirect processor for redirect (with the original request as
	 * parameter r).
	 * 
	 * @param redirectProcessorUrl
	 *            The redirect processor to use (with the original request as
	 *            parameter r).
	 * @param httpClient
	 *            The HttpClient to execute the request.
	 * @param req
	 *            The original request.
	 * @param resp
	 *            The original response.
	 * @param timeout
	 *            The timeout for request.
	 * @return True, if redirected or false, if not.
	 */
	private boolean redirectRequest(String redirectProcessorUrl, CloseableHttpClient httpClient, HttpServletRequest req, HttpServletResponse resp, int timeout) {
		try {
			HttpGet httpGet = new HttpGet(redirectProcessorUrl);
			httpGet.setConfig(RequestConfig.custom().setRedirectsEnabled(false).setSocketTimeout(timeout).build());

			// copy all headers from original request
			final Enumeration<String> headers = req.getHeaderNames();
			while (headers.hasMoreElements()) {
				final String header = headers.nextElement();
				if (header.equalsIgnoreCase("host")) {
					continue;
				}
				final Enumeration<String> values = req.getHeaders(header);
				while (values.hasMoreElements()) {
					final String value = values.nextElement();
					httpGet.setHeader(header, value);
				}
			}

			// to remove host header
			if (httpGet.getHeaders("host") != null) {
				httpGet.removeHeaders("host");
			}
			// to add X-gbi-key header
			httpGet.setHeader(GBI_KEY_HEADER, xGbiKey);
			CloseableHttpResponse response = httpClient.execute(httpGet);
			int statusCode = response.getStatusLine().getStatusCode();
			logger.info(String.format("status code :%d", statusCode));
			if (statusCode >= 300 && statusCode < 400) {
				// if status code is 3XX, the Location header of response is set
				String location = response.getHeaders("Location")[0].getValue();
				resp.sendRedirect(location);
				return true;
			}
		}
		catch (Exception e) {
			logger.error(String.format("error while trying to request redirect:[%s]", e.getMessage()), e);
		}
		return false;
	}

	/**
	 * if there is no redirect found, use the configured default 404 page
	 * 
	 * @param req
	 *            The original request.
	 * @param resp
	 *            The original response.
	 */
	private void redirectDefault404Page(HttpServletRequest req, HttpServletResponse resp) {
		if (default404Page != null) {
			try {
				req.getRequestDispatcher(default404Page).forward(req, resp);
			}
			catch (Exception e) {
				// ensure your default 404 page is always up
			}
		}
		else {
			// you should have a default 404 page
		}
	}

	/**
	 * Splits the given String into a List by means of the specified separator.
	 * Converts all elements to lower case, and removes all duplicates. Checks
	 * each element for a valid URL.
	 * 
	 * @param string
	 *            The string to split.
	 * @param separator
	 *            The separator to use.
	 * @return see above
	 */
	private List<String> getUrlList(String string, String separator) {

		List<String> urls = new ArrayList<String>();
		String[] stringArray = string.split(separator);
		if (stringArray == null || stringArray.length == 0) {
			return urls;
		}

		UrlValidator urlValidator = new UrlValidator(UrlValidator.ALLOW_LOCAL_URLS);

		// check duplicates
		for (String currentString : stringArray) {

			if (urlValidator.isValid(currentString) && !urls.contains(currentString)) {
				urls.add(currentString);
			}
		}
		return urls;
	}
}
