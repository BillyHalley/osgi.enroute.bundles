package osgi.enroute.web.server.provider;

import java.io.*;
import java.nio.channels.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

import javax.servlet.http.*;

import org.osgi.framework.*;
import org.osgi.namespace.extender.*;
import org.osgi.service.component.annotations.*;
import org.osgi.service.log.*;
import org.osgi.util.tracker.*;

import aQute.bnd.annotation.headers.*;
import aQute.lib.io.*;
import osgi.enroute.dto.api.*;
import osgi.enroute.http.capabilities.*;
import osgi.enroute.servlet.api.*;
import osgi.enroute.web.server.cache.*;
import osgi.enroute.webserver.capabilities.*;

@ProvideCapability(ns = ExtenderNamespace.EXTENDER_NAMESPACE, name = WebServerConstants.WEB_SERVER_EXTENDER_NAME, version = WebServerConstants.WEB_SERVER_EXTENDER_VERSION)
@RequireHttpImplementation
@Component(service = {
		ConditionalServlet.class
}, immediate = true, property = {
		"service.ranking:Integer=1000", "name=" + WebServer.NAME, "no.index=true"
}, name = WebServer.NAME, configurationPolicy = ConfigurationPolicy.OPTIONAL)
public class WebServer implements ConditionalServlet {

	static final String NAME = "osgi.enroute.simple.server";

	static final long		DEFAULT_NOT_FOUND_EXPIRATION	= TimeUnit.MINUTES.toMillis(20);
	static String			BYTE_RANGE_SET_S				= "(\\d+)?\\s*-\\s*(\\d+)?";
	static Pattern			BYTE_RANGE_SET					= Pattern.compile(BYTE_RANGE_SET_S);
	static Pattern			BYTE_RANGE						= Pattern
			.compile("bytes\\s*=\\s*(\\d+)?\\s*-\\s*(\\d+)?(?:\\s*,\\s*(\\d+)\\s*-\\s*(\\d+)?)*\\s*");
	static SimpleDateFormat	format							= new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",
			Locale.ENGLISH);
	Map<String,FileCache>		cached							= new HashMap<String,FileCache>();
	LogService				log;
	DTOs					dtos;
	Cache					cache;

	static class Range {
		Range	next;
		long	start;
		long	end;

		public long length() {
			if (next == null)
				return end - start;

			return next.length() + end - start;
		}

		Range(String range, long length) {
			if (range != null) {
				if (!BYTE_RANGE.matcher(range).matches())
					throw new IllegalArgumentException("Bytes ranges does not match specification " + range);

				Matcher m = BYTE_RANGE_SET.matcher(range);
				m.find();
				init(m, length);
			} else {
				start = 0;
				end = length;
			}
		}

		private Range() {}

		void init(Matcher m, long length) {
			String s = m.group(1);
			String e = m.group(2);
			if (s == null && e == null)
				throw new IllegalArgumentException("Invalid range, both begin and end not specified: " + m.group(0));

			if (s == null) { // -n == l-n -> l
				start = length - Long.parseLong(e);
				end = length - 1;
			} else if (e == null) { // n- == n -> l
				start = Long.parseLong(s);
				end = length - 1;
			} else {
				start = Long.parseLong(s);
				end = Long.parseLong(e);
			}
			end++; // e is specified as inclusive, Java uses exclusive

			if (end > length)
				end = length;

			if (start < 0)
				start = 0;

			if (start >= end)
				throw new IllegalArgumentException("Invalid range, start higher than end " + m.group(0));

			if (m.find()) {
				next = new Range();
				next.init(m, length);
			}
		}

		void copy(FileChannel from, WritableByteChannel to) throws IOException {
			from.transferTo(start, end - start, to);
			if (next != null)
				next.copy(from, to);
		}
	}

	@interface Config {
		String[] directories() default {};

		int expires();

		boolean exceptions();

		boolean debug();

		long expiration();
	}

	Config								config;
	BundleTracker< ? >					tracker;
	private List<File>					directories	= Collections.emptyList();

	@Activate
	void activate(Config config, Map<String,Object> props, BundleContext context) throws Exception {
		this.config = config;

		String[] directories = config.directories();
		if (directories != null)
			this.directories = Stream.of(directories).map((b) -> IO.getFile(b)).collect(Collectors.toList());

		tracker = new BundleTracker<Bundle>(context, Bundle.ACTIVE | Bundle.STARTING, null) {
			public Bundle addingBundle(Bundle bundle, BundleEvent event) {
				if (bundle.getEntryPaths("static/") != null)
					return bundle;
				return null;
			}
		};
		tracker.open();
	}

	public boolean handleSecurity(HttpServletRequest request, HttpServletResponse response) throws IOException {
		return true;
	}

	@Override
	public boolean doConditionalService(HttpServletRequest rq, HttpServletResponse rsp) throws Exception {
		try {
			String path = rq.getRequestURI();
			if (path != null && path.startsWith("/"))
				path = path.substring(1);

			FileCache c = getCache(path);
			if(c == null)
				return false;

			rsp.setDateHeader("Last-Modified", c.time);
			rsp.setHeader("Etag", c.etag);
			rsp.setHeader("Content-MD5", c.md5);
			rsp.setHeader("Allow", "GET, HEAD");
			rsp.setHeader("Accept-Ranges", "bytes");

			long diff = 0;
			if (c.expiration != 0)
				diff = c.expiration - System.currentTimeMillis();
			else {
				diff = config.expiration();
				if (diff == 0)
					diff = 120000;
			}

			if (diff > 0) {
				rsp.setHeader("Cache-Control", "max-age=" + diff / 1000);
			}

			if (c.mime != null)
				rsp.setContentType(c.mime);

			Range range = new Range(rq.getHeader("Range"), c.file.length());
			long length = range.length();
			if (length >= Integer.MAX_VALUE)
				throw new IllegalArgumentException("Range to read is too high: " + length);

			rsp.setContentLength((int) range.length());

			if (config.expires() != 0) {
				Date expires = new Date(System.currentTimeMillis() + 60000 * config.expires());
				rsp.setHeader("Expires", format.format(expires));
			}

			String ifModifiedSince = rq.getHeader("If-Modified-Since");
			if (ifModifiedSince != null) {
				long time = 0;
				try {
					synchronized (format) {
						time = format.parse(ifModifiedSince).getTime();
					}
					if (time > c.time) {
						rsp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
						return true;
					}
				}
				catch (Exception e) {
					// e.printStackTrace();
				}
			}

			String ifNoneMatch = rq.getHeader("If-None-Match");
			if (ifNoneMatch != null) {
				if (ifNoneMatch.indexOf(c.etag) >= 0) {
					rsp.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
					return true;
				}
			}

			if (rq.getMethod().equalsIgnoreCase("GET")) {

				rsp.setContentLengthLong(range.length());
				OutputStream out = rsp.getOutputStream();

				try (FileInputStream file = new FileInputStream(c.file);) {
					FileChannel from = file.getChannel();
					WritableByteChannel to = Channels.newChannel(out);
					range.copy(from, to);
					from.close();
					to.close();
				}

				out.flush();
				out.close();
				rsp.getOutputStream().flush();
				rsp.getOutputStream().close();
			}

			if (c.is404)
				return false;
			else
				rsp.setStatus(HttpServletResponse.SC_OK);

		}
		catch (RedirectException e) {
			rsp.sendRedirect(e.getPath());
		}
		catch (Exception e) {
			log.log(LogService.LOG_ERROR, "Internal webserver error", e);
			if (config.exceptions())
				throw new RuntimeException(e);

			try {
				PrintWriter pw = rsp.getWriter();
				pw.println("Internal server error\n");
				rsp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}
			catch (Exception ee) {
				log.log(LogService.LOG_ERROR, "Second level internal webserver error", ee);
			}
		}
		return true;
	}

	FileCache getCache(String path) throws Exception {
		FileCache c;
		synchronized (cached) {
			c = cached.get(path);
			if (c == null || c.isExpired()) {
				c = find(path);
				if (c == null) {
					c = do404(path);
				} else
					cached.put(path, c);
			}
		}
		return c;
	}

	private FileCache do404(String path) throws Exception {
		log.log(LogService.LOG_INFO, "404 " + path);
		FileCache c = find("404.html");
		if (c == null)
			c = findBundle("default/404.html");
		if (c != null)
			c.is404 = true;

		return c;
	}

	FileCache find(String path) throws Exception {
		FileCache c = findFile(path);
		if (c != null)
			return c;
		return findBundle(path);
	}

	FileCache findFile(String path) throws Exception {
		if (config.directories() != null)
			for (File base : directories) {
				File f = IO.getFile(base, path);

				if (f.isDirectory())
					f = new File(f, "index.html");

				if (f.isFile()) {
					return cache.newFileCache(f);
				}
			}
		return null;
	}

	FileCache findBundle(String path) throws Exception {
		Bundle[] bundles = tracker.getBundles();
		if (bundles != null) {
			for (Bundle b : bundles) {
				FileCache c = cache.getFromBundle(b, path);
				if(c != null)
					return c;
			}
		}
		return null;
	}

	//-------------- PLUGIN-CACHE --------------
	public File getFile(String path) throws Exception {
		FileCache c = getCache(path);
		if (c == null)
			return null;

		if (!c.isSynched())
			return null;

		return c.file;
	}


	@Deactivate
	void deactivate() {
		tracker.close();
	}

	@Reference
	void setLog(LogService log) {
		this.log = log;
	}

	@Reference
	void setDTOs(DTOs dtos) {
		this.dtos = dtos;
	}

	@Reference
	void setCache(Cache cache) {
		this.cache = cache;
	}
}