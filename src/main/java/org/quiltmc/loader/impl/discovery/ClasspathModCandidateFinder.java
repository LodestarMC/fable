/*
 * Copyright 2016 FabricMC
 * Copyright 2023 QuiltMC
 * Copyright 2023 Tomat and fable contributors
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

package org.quiltmc.loader.impl.discovery;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.launch.common.QuiltLauncherBase;
import org.quiltmc.loader.impl.util.LoaderUtil;
import org.quiltmc.loader.impl.util.QuiltLoaderInternal;
import org.quiltmc.loader.impl.util.QuiltLoaderInternalType;
import org.quiltmc.loader.impl.util.SystemProperties;
import org.quiltmc.loader.impl.util.UrlConversionException;
import org.quiltmc.loader.impl.util.UrlUtil;
import org.quiltmc.loader.impl.util.log.Log;
import org.quiltmc.loader.impl.util.log.LogCategory;

@QuiltLoaderInternal(QuiltLoaderInternalType.LEGACY_EXPOSED)
public class ClasspathModCandidateFinder {

	@FunctionalInterface
	public interface ModAdder {
		void addMod(List<Path> paths);
	}

	public static void findCandidatesStatic(ModAdder out) {
		if (QuiltLauncherBase.getLauncher().isDevelopment()) {
			Map<Path, List<Path>> pathGroups = getPathGroups();

			// Search for URLs which point to 'fabric.mod.json' entries, to be considered as mods.
			try {
				Enumeration<URL> fabricMods = QuiltLauncherBase.getLauncher().getTargetClassLoader().getResources("fabric.mod.json");
				Enumeration<URL> quiltMods = QuiltLauncherBase.getLauncher().getTargetClassLoader().getResources("quilt.mod.json");
				Enumeration<URL> fableMods = QuiltLauncherBase.getLauncher().getTargetClassLoader().getResources("fable.mod.json");
				while (quiltMods.hasMoreElements()) {
					URL url = quiltMods.nextElement();

					try {
						Path path = LoaderUtil.normalizeExistingPath(UrlUtil.getCodeSource(url, "quilt.mod.json"));
						List<Path> paths = pathGroups.get(path);

						if (paths == null) {
							out.addMod(Collections.singletonList(path));
						} else {
							out.addMod(paths);
						}
					} catch (UrlConversionException e) {
						Log.debug(LogCategory.DISCOVERY, "Error determining location for quilt.mod.json from %s", url, e);
					}
				}
				while (fabricMods.hasMoreElements()) {
					URL url = fabricMods.nextElement();

					try {
						Path path = LoaderUtil.normalizeExistingPath(UrlUtil.getCodeSource(url, "fabric.mod.json"));
						List<Path> paths = pathGroups.get(path);

						if (paths == null) {
							out.addMod(Collections.singletonList(path));
						} else {
							out.addMod(paths);
						}
					} catch (UrlConversionException e) {
						Log.debug(LogCategory.DISCOVERY, "Error determining location for fabric.mod.json from %s", url, e);
					}
				}
				while (fableMods.hasMoreElements()) {
					URL url = fableMods.nextElement();

					try {
						Path path = LoaderUtil.normalizeExistingPath(UrlUtil.getCodeSource(url, "fable.mod.json"));
						List<Path> paths = pathGroups.get(path);

						if (paths == null) {
							out.addMod(Collections.singletonList(path));
						} else {
							out.addMod(paths);
						}
					} catch (UrlConversionException e) {
						Log.debug(LogCategory.DISCOVERY, "Error determining location for fable.mod.json from %s", url, e);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else { // production, add loader as a mod
			try {
				out.addMod(Collections.singletonList(getLoaderPath()));
			} catch (Throwable t) {
				Log.debug(LogCategory.DISCOVERY, "Could not retrieve launcher code source!", t);
			}
		}
	}

	/**
	 * Parse fabric.classPathGroups system property into a path group lookup map.
	 *
	 * <p>This transforms {@code a:b::c:d:e} into {@code a=[a,b],b=[a,b],c=[c,d,e],d=[c,d,e],e=[c,d,e]}
	 */
	private static Map<Path, List<Path>> getPathGroups() {
		String prop = System.getProperty(SystemProperties.PATH_GROUPS);
		if (prop == null) return Collections.emptyMap();

		Set<Path> cp = new HashSet<>(QuiltLauncherBase.getLauncher().getClassPath());
		Map<Path, List<Path>> ret = new HashMap<>();

		for (String group : prop.split(File.pathSeparator+File.pathSeparator)) {
			Set<Path> paths = new LinkedHashSet<>();

			for (String path : group.split(File.pathSeparator)) {
				if (path.isEmpty()) continue;

				Path resolvedPath = Paths.get(path);

				if (!Files.exists(resolvedPath)) {
					Log.warn(LogCategory.DISCOVERY, "Skipping missing class path group entry %s", path);
					continue;
				}

				resolvedPath = LoaderUtil.normalizeExistingPath(resolvedPath);

				if (cp.contains(resolvedPath)) {
					paths.add(resolvedPath);
				}
			}

			if (paths.size() < 2) {
				Log.debug(LogCategory.DISCOVERY, "Skipping class path group with no effect: %s", group);
				continue;
			}

			List<Path> pathList = new ArrayList<>(paths);

			for (Path path : pathList) {
				ret.put(path, pathList);
			}
		}

		return ret;
	}


	public static Path getLoaderPath() {
		try {
			return UrlUtil.asPath(QuiltLauncherBase.getLauncher().getClass().getProtectionDomain().getCodeSource().getLocation());
		} catch (Throwable t) {
			Log.debug(LogCategory.DISCOVERY, "Could not retrieve launcher code source!", t);
			return null;
		}
	}

	public static Path getGameProviderPath() {
		try {
			return UrlUtil.asPath(QuiltLoaderImpl.INSTANCE.getGameProvider().getClass().getProtectionDomain().getCodeSource().getLocation());
		} catch (Throwable t) {
			Log.debug(LogCategory.DISCOVERY, "Could not retrieve launcher code source!", t);
			return null;
		}
	}
}
