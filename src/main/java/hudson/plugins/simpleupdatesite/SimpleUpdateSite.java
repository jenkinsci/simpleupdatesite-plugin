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

import hudson.PluginWrapper;
import hudson.model.Hudson;
import hudson.model.UpdateSite;
import hudson.util.TextFile;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * UpdateSite extension to hack default {@link UpdateSite} behavior
 * 
 * @author JunHo Yoon
 */
public class SimpleUpdateSite extends UpdateSite {
	public SimpleUpdateSite(String id) {
		super(id, null);
	}

	public SimpleUpdateSite(String id, String url) {
		super(id, url);
	}

	@Override
	public String getUrl() {
		return getPlugin().getUpdateSiteUrl();
	}

	protected SimpleUpdateSitePlugIn getPlugin() {
		return Hudson.getInstance().getPlugin(SimpleUpdateSitePlugIn.class);
	}

	@Override
	public boolean isLegacyDefault() {
		return false;
	}
	/**
	 * This is the endpoint that receives the update center data file from the
	 * browser.
	 */
	public void doPostBack(StaplerRequest req, StaplerResponse rsp, @QueryParameter String json) throws IOException, GeneralSecurityException {
		if (StringUtils.isEmpty(json) && req != null) {
			json = org.apache.commons.io.IOUtils.toString(req.getInputStream(), "UTF-8");
		}
		if (StringUtils.isNotBlank(json)) {
			getDataFile().write(json);
		}
		if (rsp != null) {
			rsp.setContentType("text/plain");
		}
	}

	/**
	 * This is where we store the update center data. Because getDataFile in
	 * {@link UpdateSite} class is private, the function should be provided in
	 * this class.
	 */
	public TextFile getDataFile() {
		return new TextFile(new File(Hudson.getInstance().getRootDir(), "updates/" + getId() + ".json"));
	}

	public List<Plugin> getInstalled() {
		List<Plugin> installedPlugins = new ArrayList<Plugin>();
		Data data = getData();
		if (data == null) {
			return Collections.emptyList();
		}
		for (Plugin plugin : data.plugins.values()) {
			if (plugin.getInstalled() != null) {
				installedPlugins.add(plugin);
			}
		}
		return installedPlugins;
	}

	/**
	 * Returns the list of plugins that are updates to currently installed ones.
	 * 
	 * @return can be empty but never null.
	 */
	@Override
	public List<Plugin> getUpdates() {
		Data data = getData();
		if (data == null) {
			return Collections.emptyList(); // fail to determine
		}

		List<Plugin> updatablePlugins = new ArrayList<Plugin>();
		for (Plugin plugin : data.plugins.values()) {
			PluginWrapper installed = plugin.getInstalled();
			if (installed != null && isNewerPlugin(plugin.version, installed.getVersion())) {
				updatablePlugins.add(plugin);
			}
		}
		return updatablePlugins;
	}

	public boolean isNewerPlugin(String newVersion, String installedVersion) {
		installedVersion = installedVersion.replaceAll("\\(.*\\)", "").trim();
		newVersion = newVersion.replaceAll("\\(.*\\)", "").trim();
		return new VersionNumber(newVersion).compareTo(new VersionNumber(installedVersion)) > 0;
	}
}
