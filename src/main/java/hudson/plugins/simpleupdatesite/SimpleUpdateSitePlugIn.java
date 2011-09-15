/*
 * The MIT License
 *
 * Copyright (c) 2004-, all the contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.simpleupdatesite;

import hudson.Plugin;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.UpdateCenter;
import hudson.model.UpdateSite;
import hudson.plugins.simpleupdatesite.model.PluginEntry;
import hudson.plugins.simpleupdatesite.model.RssEntry;
import hudson.plugins.simpleupdatesite.util.TimeoutReference;
import hudson.plugins.simpleupdatesite.util.TimeoutReference.ReferenceRetriever;
import hudson.util.FormValidation;
import hudson.util.PersistedList;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.io.FeedException;
import com.sun.syndication.io.SyndFeedInput;
import com.sun.syndication.io.XmlReader;

/**
 * SimpleUpdateSite Plugin which retrieves updatesite.json and news content from
 * custom location
 * 
 * @author JunHo Yoon
 */
public class SimpleUpdateSitePlugIn extends Plugin {
	public SimpleUpdateSitePlugIn() {
	}

	private static final Logger LOGGER = Logger.getLogger(SimpleUpdateSitePlugIn.class.getName());

	TimeoutReference<List<RssEntry>> rssEntryReference = new TimeoutReference<List<RssEntry>>(Constant.UPDATESITE_REFRESH_RATE,
		new ReferenceRetriever<List<RssEntry>>() {
			public List<RssEntry> createReference() {
				String rssUrl = getNewsRssUrl();
				if (StringUtils.isNotBlank(rssUrl)) {
					List<RssEntry> rssEntries;
					try {
						rssEntries = getRssEntries(rssUrl);
						SimpleUpdateSitePlugIn.this.rssEntryReference.put(rssEntries);
						return rssEntries;
					} catch (Exception e) {
						diagnoseNewsRssSiteUrl(getNewsRssUrl());
						SimpleUpdateSitePlugIn.LOGGER.log(Level.SEVERE, "Error while get rss entries", e);
					}
				}
				return Collections.emptyList();
			}
		});

	TimeoutReference<List<PluginEntry>> pluginEntryReference = new TimeoutReference<List<PluginEntry>>(Constant.UPDATESITE_REFRESH_RATE,
		new ReferenceRetriever<List<PluginEntry>>() {
			public List<PluginEntry> createReference() {
				try {
					List<PluginEntry> pluginEntries = getPluginEntries();
					SimpleUpdateSitePlugIn.this.pluginEntryReference.put(pluginEntries);
					return pluginEntries;
				} catch (Exception e) {
					SimpleUpdateSitePlugIn.LOGGER.log(Level.SEVERE, "Error while get plugin entries", e);
				}
				return Collections.emptyList();
			}
		});

	@SuppressWarnings("unchecked")
	private List<RssEntry> getRssEntries(String url) throws MalformedURLException, FeedException, IOException {
		URL feedUrl = new URL(url);
		SyndFeedInput input = new SyndFeedInput();
		XmlReader reader = null;
		List<RssEntry> entries = new ArrayList<RssEntry>();
		try {
			reader = new XmlReader(feedUrl);
			SyndFeed syndFeeds = input.build(reader);
			int index = 0;
			for (SyndEntry syndEntry : (List<SyndEntry>) syndFeeds.getEntries()) {
				RssEntry entry = new RssEntry();
				entry.setTitle(syndEntry.getTitle());
				entry.setUrl(syndEntry.getLink());
				entry.setUpdatedDate(syndEntry.getUpdatedDate() == null ? syndEntry.getPublishedDate() : syndEntry.getUpdatedDate());
				entries.add(entry);
				if (++index >= 10) {
					break;
				}
			}
		} finally {
			if (reader != null) {
				reader.close();
			}
		}
		return entries;
	}

	/**
	 * Register {@link SimpleUpdateSite}
	 */
	@Override
	public void postInitialize() throws Exception {
		load();
		UpdateCenter updateCenter = Hudson.getInstance().getUpdateCenter();
		updateCenter.load();
		// Reorder update sites
		PersistedList<UpdateSite> updateSites = updateCenter.getSites();
		List<UpdateSite> sites = updateSites.toList();
		updateSites.clear();
		updateSites.add(new SimpleUpdateSite(Constant.SIMPLE_UPDATESITE_ID));
		for (UpdateSite site : sites) {
			if (!site.getId().equals(Constant.SIMPLE_UPDATESITE_ID)) {
				updateSites.add(site);
			}
		}

		diagnoseNewsRssSiteUrl(getNewsRssUrl());
		diagnoseUpdateSiteUrl(getUpdateSiteUrl());
		super.postInitialize();
	}

	private String newsRssUrl;
	private String updateSiteUrl;
	private String supportUrl;
	private final Set<String> hiddenPluginList = new HashSet<String>();

	private transient boolean updateSiteValid = true;
	private transient String updateSiteFailCause = "";
	private transient boolean newsRssSiteValid = true;
	private transient String newsRssSiteFailCause = "";

	@Override
	public void configure(StaplerRequest req, JSONObject json) throws IOException, ServletException, FormException {
		super.configure(req, json);

		String newsRssUrl = req.getParameter("simpleupdatesite.newsRssUrl");
		newsRssUrl = StringUtils.trim(newsRssUrl);
		if (!StringUtils.equals(newsRssUrl, getNewsRssUrl())) {
			diagnoseNewsRssSiteUrl(newsRssUrl);
			setNewsRssUrl(newsRssUrl);
			this.rssEntryReference.invalidate();

		}
		String updateSiteUrl = req.getParameter("simpleupdatesite.updateSiteUrl");
		updateSiteUrl = StringUtils.trim(updateSiteUrl);
		if (!StringUtils.equals(updateSiteUrl, getUpdateSiteUrl())) {
			diagnoseUpdateSiteUrl(updateSiteUrl);
			setUpdateSiteUrl(updateSiteUrl);
			downloadUpdateSiteJSON();
			this.pluginEntryReference.invalidate();
		}

		supportUrl = StringUtils.trimToNull(req.getParameter("simpleupdatesite.supportUrl"));
		save();
	}

	public boolean diagnoseNewsRssSiteUrl(String newsRssUrl) {
		String dianosis = diagnoseUrl(newsRssUrl, "News RSS");
		if (dianosis == null) {
			setNewsRssSiteValid(true);
		} else {
			setNewsRssSiteValid(false);
			setNewsRssSiteFailCause(dianosis);
		}
		return isNewsRssSiteValid();
	}

	public boolean diagnoseUpdateSiteUrl(String updateSiteUrl) {
		String dianosis = diagnoseUrl(updateSiteUrl, "Update Site");
		if (dianosis == null) {
			setUpdateSiteValid(true);
		} else {
			setUpdateSiteValid(false);
			setUpdateSiteFailCause(dianosis);
		}
		return isUpdateSiteValid();
	}

	public String diagnoseUrl(String urlString, String siteName) {
		try {
			if (StringUtils.isBlank(urlString)) {
				return String.format("<b>Empty %s Url</b><br/>- Please set it in ", siteName);
			}
			checkConnection(urlString);
		} catch (IllegalArgumentException e) {
			return String.format("<b>Wrong %s Url (IllegalArgumentException)</b> (%s)<br/>- Please fix it in ", siteName, urlString);
		} catch (HttpException e) {
			return String.format("<b>Wrong %s Url (HttpException)</b> (%s)<br/>- Please fix it in ", siteName, urlString);
		} catch (IOException e) {
			SimpleUpdateSitePlugIn.LOGGER.log(Level.SEVERE, String.format("Error on %s", siteName), e);
			return String.format("<b>Incorrect contents on %s</b> (%s)<br/>- Please fix it in ", siteName, urlString);
		} finally {
		}
		return null;
	}

	public FormValidation doRefreshPluginInfo(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
		try {
			downloadUpdateSiteJSONForce();
			this.pluginEntryReference.invalidate();
			setUpdateSiteValid(true);
			return FormValidation.ok(" Refresh completed. Refresh the browser");
		} catch (IOException e) {
			return FormValidation.error("Refresh error - cause : " + e.getMessage());
		}
	}

	public FormValidation doRefreshNews(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
		this.rssEntryReference.invalidate();
		setNewsRssSiteValid(true);
		return FormValidation.ok(" Refresh completed. Refresh the browser");
	}

	@SuppressWarnings("unchecked")
	public FormValidation doHidePlugins(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
		Enumeration<String> en = req.getParameterNames();
		int count = 0;
		while (en.hasMoreElements()) {
			String next = en.nextElement();
			if (next.startsWith("plugin.")) {
				next = next.substring(7);
				if (next.indexOf(".") >= 0 && next.indexOf(".") != 0) {
					String[] pluginInfo = next.split("\\.");
					this.hiddenPluginList.add(pluginInfo[0]);
					count++;
				}
			}
		}
		save();
		return FormValidation.ok(String.format("%d plugins are hidden.", count));
	}

	public FormValidation doShowAllHiddenPlugins(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
		this.hiddenPluginList.clear();
		save();
		return FormValidation.ok("All hidden plugins are shown.");
	}

	public void setUpdateSiteUrl(String updateSiteUrl) {
		this.updateSiteUrl = updateSiteUrl;
	}

	public String getUpdateSiteUrl() {
		return this.updateSiteUrl;
	}

	public void setNewsRssUrl(String newsRssUrl) {
		this.newsRssUrl = newsRssUrl;
	}

	public String getNewsRssUrl() {
		return this.newsRssUrl;
	}

	private void writeUpdateSiteInfo(String json) throws IOException, GeneralSecurityException {
		((SimpleUpdateSite) Hudson.getInstance().getUpdateCenter().getById(Constant.SIMPLE_UPDATESITE_ID)).doPostBack(null, null, json);
	}

	public String checkConnection(String url) throws HttpException, IOException, IllegalArgumentException {
		GetMethod method = new GetMethod(url);
		InputStream responseBodyAsStream = null;
		try {
			method.getParams().setCookiePolicy(CookiePolicy.IGNORE_COOKIES);
			HttpClient client = new HttpClient();
			client.getParams().setConnectionManagerTimeout(1000);
			client.getParams().setSoTimeout(1000);
			DefaultHttpMethodRetryHandler handler = new DefaultHttpMethodRetryHandler(1, false);
			client.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, handler);
			client.executeMethod(method);
			if (method.getStatusCode() >= 300) {
				throw new HttpException();
			}
			responseBodyAsStream = method.getResponseBodyAsStream();
			return IOUtils.toString(responseBodyAsStream, "UTF-8");

		} finally {
			IOUtils.closeQuietly(responseBodyAsStream);
			method.releaseConnection();
		}
	}

	public String getUpdateSiteJSON(String url) throws HttpException, IOException {
		GetMethod method = new GetMethod(url);
		InputStream responseBodyAsStream = null;
		try {
			method.getParams().setCookiePolicy(CookiePolicy.IGNORE_COOKIES);
			HttpClient client = new HttpClient();
			client.getParams().setConnectionManagerTimeout(Constant.HTTP_TIMEOUT);
			client.getParams().setSoTimeout(Constant.HTTP_TIMEOUT);
			DefaultHttpMethodRetryHandler handler = new DefaultHttpMethodRetryHandler(2, false);
			client.getParams().setParameter(HttpMethodParams.RETRY_HANDLER, handler);
			client.executeMethod(method);
			responseBodyAsStream = method.getResponseBodyAsStream();
			return IOUtils.toString(responseBodyAsStream, "UTF-8");
		} finally {
			IOUtils.closeQuietly(responseBodyAsStream);
			method.releaseConnection();
		}
	}

	public void downloadUpdateSiteJSON() throws HttpException, IOException {
		String updateCenterUrl = getUpdateSiteUrl();
		if (!this.updateSiteValid || StringUtils.isBlank(updateCenterUrl)) {
			return;
		}
		downloadUpdateSiteJSONForce();
		SimpleUpdateSitePlugIn.LOGGER.log(Level.INFO, "fetching update json is finished");

	}

	public void downloadUpdateSiteJSONForce() throws HttpException, IOException {
		SimpleUpdateSitePlugIn.LOGGER.log(Level.INFO, "fetching update json from " + getUpdateSiteUrl());
		String json = getUpdateSiteJSON(getUpdateSiteUrl());
		try {
			json = stripOutCallBackMethod(json);
			writeUpdateSiteInfo(json);
		} catch (GeneralSecurityException e) {
			SimpleUpdateSitePlugIn.LOGGER.log(Level.SEVERE, "writing update json is failed ", e);
		}
	}

	public String stripOutCallBackMethod(String json) {
		json = StringUtils.trimToEmpty(json);
		String prefix = "updateCenter.post(";
		String suffix = ");";
		if (json.startsWith(prefix) && json.endsWith(suffix)) {
			json = json.substring(prefix.length(), json.length() - suffix.length());
		}
		return json;
	}

	public List<RssEntry> getRssEntryReference() {
		return this.rssEntryReference.get();
	}

	public List<PluginEntry> getShownPluginEntryReference() {
		List<PluginEntry> pluginEntries = new ArrayList<PluginEntry>();
		List<PluginEntry> list = this.pluginEntryReference.get();
		if (list != null) {
			for (PluginEntry pluginEntry : list) {
				if (!this.hiddenPluginList.contains(pluginEntry.getName())) {
					pluginEntries.add(pluginEntry);
				}
			}
		}
		return pluginEntries;
	}

	private List<PluginEntry> getPluginEntries() {
		List<PluginEntry> entries = new ArrayList<PluginEntry>();
		for (UpdateSite.Plugin plugin : getAvaliablePlugin()) {
			entries.add(new PluginEntry(plugin, false));
		}
		Set<String> updatedPluginKey = new HashSet<String>();
		for (UpdateSite.Plugin plugin : getUpdatedPlugin()) {
			entries.add(new PluginEntry(plugin, true));
			updatedPluginKey.add(plugin.name);
		}

		for (UpdateSite.Plugin plugin : getInstalledPlugin()) {
			if (!updatedPluginKey.contains(plugin.name)) {
				entries.add(new PluginEntry(plugin, true, true));
			}
		}
		return entries;
	}

	protected List<UpdateSite.Plugin> getInstalledPlugin() {
		List<UpdateSite.Plugin> availablePlugins = new ArrayList<UpdateSite.Plugin>();
		SimpleUpdateSite updateSite = (SimpleUpdateSite) Hudson.getInstance().getUpdateCenter().getById(Constant.SIMPLE_UPDATESITE_ID);
		for (UpdateSite.Plugin each : updateSite.getInstalled()) {
			if (Constant.SIMPLE_UPDATESITE_ID.equals(each.sourceId)) {
				availablePlugins.add(each);
			}
		}
		return availablePlugins;
	}

	protected List<UpdateSite.Plugin> getAvaliablePlugin() {
		List<UpdateSite.Plugin> availablePlugins = new ArrayList<UpdateSite.Plugin>();
		for (UpdateSite.Plugin each : Hudson.getInstance().getUpdateCenter().getById(Constant.SIMPLE_UPDATESITE_ID).getAvailables()) {
			if (Constant.SIMPLE_UPDATESITE_ID.equals(each.sourceId)) {
				availablePlugins.add(each);
			}
		}
		return availablePlugins;
	}

	protected List<UpdateSite.Plugin> getUpdatedPlugin() {
		List<UpdateSite.Plugin> availablePlugins = new ArrayList<UpdateSite.Plugin>();
		for (UpdateSite.Plugin each : Hudson.getInstance().getUpdateCenter().getById(Constant.SIMPLE_UPDATESITE_ID).getUpdates()) {
			if (Constant.SIMPLE_UPDATESITE_ID.equals(each.sourceId)) {
				availablePlugins.add(each);
			}
		}
		return availablePlugins;
	}

	public void setUpdateSiteValid(boolean updateSiteValid) {
		this.updateSiteValid = updateSiteValid;
	}

	public boolean isUpdateSiteValid() {
		return this.updateSiteValid;
	}

	public void setUpdateSiteFailCause(String updateSiteFailCause) {
		this.updateSiteFailCause = updateSiteFailCause;
	}

	public String getUpdateSiteFailCause() {
		return this.updateSiteFailCause;
	}

	public void setNewsRssSiteValid(boolean newsRssSiteValid) {
		this.newsRssSiteValid = newsRssSiteValid;
	}

	public boolean isNewsRssSiteValid() {
		return this.newsRssSiteValid;
	}

	public void setNewsRssSiteFailCause(String newsRssSiteFailCause) {
		this.newsRssSiteFailCause = newsRssSiteFailCause;
	}

	public String getNewsRssSiteFailCause() {
		return this.newsRssSiteFailCause;
	}

	public String getSupportUrl() {
		return supportUrl;
	}

}
