/*
 * Fable - quilt-/fabric-loader fork; <https://github.com/steviegt6/fable>
 * Copyright (C) 2024  QuiltMC
 * Copyright (C) 2024  Tomat et al.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.quiltmc.loader.impl.util;

import java.io.File;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;

@QuiltLoaderInternal(QuiltLoaderInternalType.LEGACY_EXPOSED)
public final class UrlUtil {
	public static final Path LOADER_CODE_SOURCE = getCodeSource(UrlUtil.class);
	private UrlUtil() { }

	public static URL getSource(String filename, URL resourceURL) throws UrlConversionException {
		URL codeSourceURL;

		try {
			URLConnection connection = resourceURL.openConnection();

			if (connection instanceof JarURLConnection) {
				codeSourceURL = ((JarURLConnection) connection).getJarFileURL();
			} else {
				String path = resourceURL.getPath();

				if (path.endsWith(filename)) {
					codeSourceURL = new URL(resourceURL.getProtocol(), resourceURL.getHost(), resourceURL.getPort(), path.substring(0, path.length() - filename.length()));
				} else {
					throw new UrlConversionException("Could not figure out code source for file '" + filename + "' and URL '" + resourceURL + "'!");
				}
			}
		} catch (Exception e) {
			throw new UrlConversionException(e);
		}

		return codeSourceURL;
	}

	public static Path getSourcePath(String filename, URL resourceURL) throws UrlConversionException {
		try {
			return asPath(getSource(filename, resourceURL));
		} catch (ExceptionUtil.WrappedException e) {
			throw new UrlConversionException(e);
		}
	}

	public static Path asPath(URL url) {
		try {
			return Paths.get(url.toURI());
		} catch (URISyntaxException e) {
			throw ExceptionUtil.wrap(e);
		}
	}


	public static URL asUrl(File file) throws MalformedURLException {
		return file.toURI().toURL();
	}

	public static URL asUrl(Path path) throws MalformedURLException {
		return LoaderUtil.normalizePath(path).toUri().toURL();
	}

	public static Path getCodeSource(URL url, String localPath) throws UrlConversionException {
		try {
			URLConnection connection = url.openConnection();

			if (connection instanceof JarURLConnection) {
				return asPath(((JarURLConnection) connection).getJarFileURL());
			} else {
				String path = url.getPath();

				if (path.endsWith(localPath)) {
					return asPath(new URL(url.getProtocol(), url.getHost(), url.getPort(), path.substring(0, path.length() - localPath.length())));
				} else {
					throw new UrlConversionException("Could not figure out code source for file '" + localPath + "' in URL '" + url + "'!");
				}
			}
		} catch (Exception e) {
			throw new UrlConversionException(e);
		}
	}

	public static Path getCodeSource(Class<?> cls) {
		CodeSource cs = cls.getProtectionDomain().getCodeSource();
		if (cs == null) return null;

		return asPath(cs.getLocation());
	}
}
