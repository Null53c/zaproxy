/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
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
package org.zaproxy.zap.extension.httppanel.component.split.request;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.zaproxy.zap.extension.httppanel.view.impl.models.http.request.RequestHeaderStringHttpPanelViewModel;
import org.zaproxy.zap.extension.httppanel.view.text.HttpPanelTextArea;
import org.zaproxy.zap.extension.httppanel.view.text.HttpPanelTextView;
import org.zaproxy.zap.extension.httppanel.view.util.HttpTextViewUtils;
import org.zaproxy.zap.extension.search.SearchMatch;

public class HttpRequestHeaderPanelTextView extends HttpPanelTextView {

	public HttpRequestHeaderPanelTextView(RequestHeaderStringHttpPanelViewModel model) {
		super(model);
	}
	
	@Override
	protected HttpPanelTextArea createHttpPanelTextArea() {
		return new HttpRequestHeaderPanelTextArea();
	}
	
	private static class HttpRequestHeaderPanelTextArea extends HttpPanelTextArea {

		private static final long serialVersionUID = 985537589818833350L;
		
		@Override
		public void search(Pattern p, List<SearchMatch> matches) {
			Matcher m = p.matcher(getText());
			while (m.find()) {

				int[] position = HttpTextViewUtils.getViewToHeaderPosition(this, m.start(), m.end());
				if (position.length == 0) {
					return;
				}
				
				matches.add(new SearchMatch(SearchMatch.Location.REQUEST_HEAD, position[0], position[1]));
			}
		}
		
		@Override
		public void highlight(SearchMatch sm) {
			if (!SearchMatch.Location.REQUEST_HEAD.equals(sm.getLocation())) {
				return;
			}
			
			int[] pos = HttpTextViewUtils.getHeaderToViewPosition(
					this,
					sm.getMessage().getRequestHeader().toString(),
					sm.getStart(),
					sm.getEnd());
			if (pos.length == 0) {
				return;
			}
			highlight(pos[0], pos[1]);
		}
		
	}

}

