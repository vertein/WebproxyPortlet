/**
 * Licensed to Apereo under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Apereo licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
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
package org.jasig.portlet.proxy.mvc.portlet.proxy;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import javax.annotation.Resource;
import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.EventRequest;
import javax.portlet.EventResponse;
import javax.portlet.PortletPreferences;
import javax.portlet.PortletRequest;
import javax.portlet.PortletSession;
import javax.portlet.RenderRequest;
import javax.portlet.RenderResponse;
import javax.portlet.ResourceRequest;
import javax.portlet.ResourceResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.jasig.portal.search.SearchConstants;
import org.jasig.portal.search.SearchResults;
import org.jasig.portlet.proxy.search.ISearchService;
import org.jasig.portlet.proxy.service.IContentRequest;
import org.jasig.portlet.proxy.service.IContentResponse;
import org.jasig.portlet.proxy.service.IContentService;
import org.jasig.portlet.proxy.service.proxy.document.IDocumentFilter;
import org.jasig.portlet.proxy.service.proxy.document.URLRewritingFilter;
import org.jasig.portlet.proxy.service.web.HttpContentResponseImpl;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.portlet.bind.annotation.ActionMapping;
import org.springframework.web.portlet.bind.annotation.EventMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;
import org.springframework.web.portlet.bind.annotation.ResourceMapping;

/**
 * ProxyPortletController is the main view controller for web proxy portlets.
 * 
 * @author Jen Bourey, jennifer.bourey@gmail.com
 */
@Controller
@RequestMapping("VIEW")
public class ProxyPortletController {

    protected static final String CONTENT_SERVICE_KEY = "contentService";
    protected static final String FILTER_LIST_KEY = "filters";
    public static final String PREF_CHARACTER_ENCODING = "sourcePageCharacterEncoding";
    public static final String CHARACTER_ENCODING_DEFAULT = "UTF-8";

    protected final Logger log = LoggerFactory.getLogger(this.getClass());
    private ApplicationContext applicationContext;

    @Autowired(required = true)
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    private List<Pattern> knownHtmlContentTypes = new ArrayList<Pattern>();

    @Required
    @Resource(name="knownHtmlContentTypes")
    public void setKnownHtmlContentTypes(List<String> contentTypes) {
        knownHtmlContentTypes.clear();
        for (String contentType : contentTypes) {
            knownHtmlContentTypes.add(Pattern.compile(contentType));
        }
    }

    @Resource(name="contentSearchProvider")
    private ISearchService searchService;

    @RenderMapping
    public void showContent(final RenderRequest request, final RenderResponse response) {

        // locate the content service to use to retrieve our HTML content
        final IContentService<IContentRequest,IContentResponse> contentService = selectContentService(request);

        // retrieve the HTML content
        final IContentResponse proxyResponse;
        try {
            proxyResponse = invokeProxy(contentService, request);
        } catch (Exception e) {
            log.error("Failed to proxy content", e);
            // TODO: error handling
            return;
        }

        // locate all filters configured for this portlet
        final List<IDocumentFilter> filters = prepareFilters(request);

        try {
            final Document document = parseDocument(request, proxyResponse);

            // apply each of the document filters in order
            for (final IDocumentFilter filter : filters) {
                filter.filter(document, proxyResponse, request, response);
            }

            // write out the final content
            OutputStream out = null;
            try {
                out = response.getPortletOutputStream();
                IOUtils.write(document.html(), out);
                out.flush();
            } catch (IOException e) {
                log.error("Exception writing proxied content", e);
            } finally {
                IOUtils.closeQuietly(out);
            }

        } catch (IOException e) {
            log.error("Error parsing HTML content", e);
        } finally {
            if (proxyResponse != null) {
                proxyResponse.close();
            }
        }

    }

    @ActionMapping
    public void proxyTarget(final @RequestParam("proxy.url") String url,  final ActionRequest request,
                            final ActionResponse response) throws IOException {

        IContentResponse proxyResponse = null;

        try {
          // locate the content service to use to retrieve our HTML content
          final IContentService<IContentRequest,IContentResponse> contentService = selectContentService(request);

          final IContentRequest proxyRequest;
          try {
              proxyRequest = contentService.getRequest(request);
          } catch (RuntimeException e) {
              log.error("URL {} was not in the proxy list", url);
              // TODO: how should we handle these errors?
              return;
          }

          // retrieve the HTML content
          proxyResponse = contentService.getContent(proxyRequest, request);
          log.debug("Check to see if proxy response is good");

          // TODO: this probably can only be an HTTP content type
          if (proxyResponse instanceof HttpContentResponseImpl) {
              log.debug("Proxy response is instance of httpcontentresponseimpl");
            // Determine the content type of the proxied response.  If this is
            // not an HTML type, we need to construct a resource URL instead
            final HttpContentResponseImpl httpContentResponse = (HttpContentResponseImpl) proxyResponse;
            final String responseContentType = httpContentResponse.getHeaders().get("Content-Type");
            log.debug("Proxy response content type is "+responseContentType);
            for (Pattern contentType : knownHtmlContentTypes) {
                if (responseContentType != null && contentType.matcher(responseContentType).matches()) {
                    final Map<String, String[]> params = request.getParameterMap();
                    response.setRenderParameters(params);
                    return;
                }
            }
            

          }else{
                            log.debug("Proxy response is not instance of httpcontentresponseimpl");
                         }

          // if this is not an HTML content type, use the corresponding resource
          // URL in the session
          final PortletSession session = request.getPortletSession();
          @SuppressWarnings("unchecked")
          final ConcurrentMap<String,String> rewrittenUrls = (ConcurrentMap<String,String>) session.getAttribute(URLRewritingFilter.REWRITTEN_URLS_KEY);
          log.debug("Going to redirect with : "+rewrittenUrls.get(url));
          //return;
          response.sendRedirect(rewrittenUrls.get(url));
        } finally {
            if (proxyResponse != null) {
                proxyResponse.close();
            }
        }
    }

    @ResourceMapping
    public void proxyResourceTarget(final @RequestParam("proxy.url") String url, final ResourceRequest request, final ResourceResponse response) {

        // locate the content service to use to retrieve our HTML content
        final IContentService<IContentRequest,IContentResponse> contentService = selectContentService(request);

        // construct the proxy request
        final IContentRequest proxyRequest;
        try {
            proxyRequest = contentService.getRequest(request);
        } catch (RuntimeException e) {
            log.error("URL {} was not in the proxy list", url);
            response.setProperty(ResourceResponse.HTTP_STATUS_CODE, String.valueOf(HttpServletResponse.SC_UNAUTHORIZED));
            return;
        }

        // retrieve the HTML content
        final IContentResponse proxyResponse = contentService.getContent(proxyRequest, request);
        OutputStream out = null;

        try {

            // TODO: find a cleaner way to handle this.  we probably can't ever
            // have anything except an HTTP response
            if (proxyResponse instanceof HttpContentResponseImpl) {
                // replay any response headers from the proxied target
                HttpContentResponseImpl httpProxyResponse = (HttpContentResponseImpl) proxyResponse;
                for (Map.Entry<String, String> header : httpProxyResponse.getHeaders().entrySet()) {
                    response.setProperty(header.getKey(), header.getValue());
                }
            }

            // write out all proxied content
            out = response.getPortletOutputStream();
            IOUtils.copyLarge(proxyResponse.getContent(), out);
            out.flush();

        } catch (IOException e) {
            response.setProperty(ResourceResponse.HTTP_STATUS_CODE, String.valueOf(HttpServletResponse.SC_UNAUTHORIZED));
            log.error("Exception writing proxied content", e);
        } finally {
            if (proxyResponse != null) {
                proxyResponse.close();
            }
            IOUtils.closeQuietly(out);
        }

    }

    @EventMapping(SearchConstants.SEARCH_REQUEST_QNAME_STRING)
    public void searchRequest(EventRequest request, EventResponse response) {
        log.debug("EVENT HANDLER -- START");

        // locate the content service to use to retrieve our HTML content
        final IContentService<IContentRequest,IContentResponse> contentService = selectContentService(request);

        try {
            // retrieve the HTML content
            final IContentResponse proxyResponse = invokeProxy(contentService, request);
            final Document document = parseDocument(request, proxyResponse);
            SearchResults searchResults = searchService.search(request, document);
            response.setEvent(SearchConstants.SEARCH_RESULTS_QNAME, searchResults);
        } catch (IOException e) {
            throw new RuntimeException("Search request failed", e);
        }

        log.debug("EVENT HANDLER -- END");
    }

    /*
     * Implementation (private stuff)
     */

    /**
     * @throws NoSuchBeanDefinitionException If there is no such bean
     */
    private IContentService<IContentRequest,IContentResponse> selectContentService(final PortletRequest req) {
        final PortletPreferences prefs = req.getPreferences();
        final String contentServiceKey = prefs.getValue(CONTENT_SERVICE_KEY, null);
        @SuppressWarnings("unchecked")
        final IContentService<IContentRequest,IContentResponse> rslt = applicationContext.getBean(contentServiceKey, IContentService.class);
        return rslt;
    }

    private IContentResponse invokeProxy(final IContentService<IContentRequest,IContentResponse> contentService, final PortletRequest req) {
        final IContentRequest proxyRequest;
        try {
            proxyRequest = contentService.getRequest(req);
        } catch (Exception e) {
            throw new RuntimeException("URL was not in the proxy list", e);
        }

        // retrieve the HTML content
        final IContentResponse rslt;
        try {
            rslt = contentService.getContent(proxyRequest, req);
        } catch (Exception e) {
            throw new RuntimeException("Failed to proxy content", e);
        }

        return rslt;
    }

    private List<IDocumentFilter> prepareFilters(final PortletRequest req) {
        final PortletPreferences preferences = req.getPreferences();
        final List<IDocumentFilter> rslt = new ArrayList<IDocumentFilter>();
        final String[] filterKeys = preferences.getValues(FILTER_LIST_KEY, new String[]{});
        for (final String filterKey : filterKeys) {
            final IDocumentFilter filter = applicationContext.getBean(filterKey, IDocumentFilter.class);
            rslt.add(filter);
        }
        return rslt;
    }

    private Document parseDocument(final PortletRequest req, final IContentResponse proxyResponse) throws IOException {
        final PortletPreferences preferences = req.getPreferences();
        String sourceEncodingFormat = preferences.getValue(PREF_CHARACTER_ENCODING, CHARACTER_ENCODING_DEFAULT);
        final Document rslt = Jsoup.parse(proxyResponse.getContent(), sourceEncodingFormat,
                proxyResponse.getProxiedLocation());
        return rslt;
    }

}
