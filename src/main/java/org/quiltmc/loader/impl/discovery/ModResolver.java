/*
 * Copyright 2016 FabricMC
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

import org.quiltmc.json5.exception.ParseException;

import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.game.GameProvider.BuiltinMod;
import org.quiltmc.loader.impl.launch.common.QuiltLauncher;
import org.quiltmc.loader.impl.launch.common.QuiltLauncherBase;
import org.quiltmc.loader.impl.memfilesys.QuiltMemoryFileSystem;
import org.quiltmc.loader.impl.metadata.BuiltinModMetadata;
import org.quiltmc.loader.impl.metadata.FabricLoaderModMetadata;
import org.quiltmc.loader.impl.metadata.FabricModMetadataReader;
import org.quiltmc.loader.impl.metadata.ParseMetadataException;
import org.quiltmc.loader.impl.metadata.qmj.ModMetadataReader;
import org.quiltmc.loader.impl.metadata.qmj.ModProvided;
import org.quiltmc.loader.impl.solver.ModSolveResult;
import org.quiltmc.loader.impl.solver.ModSolver;
import org.quiltmc.loader.impl.util.FileSystemUtil;
import org.quiltmc.loader.impl.util.UrlConversionException;
import org.quiltmc.loader.impl.util.UrlUtil;

import org.quiltmc.loader.impl.util.log.Log;
import org.quiltmc.loader.impl.util.log.LogCategory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipError;


/** The main "resolver" for mods. This class has 1 job: to find valid mod jar files from the filesystem and classpath,
 * and loading them into memory. This also includes loading mod jar files from within jar files. The main entry point
 * for the first job is {@link #resolve(QuiltLoaderImpl)} which performs all of the work for loading mods. */
public class ModResolver {
	// nested JAR store
	private static final FileSystem inMemoryFs = new QuiltMemoryFileSystem.ReadWrite("nestedJarStore");
	private static final Map<String, List<Path>> inMemoryCache = new ConcurrentHashMap<>();
	private static final Map<String, String> readableNestedJarPaths2 = new ConcurrentHashMap<>();
	private static final Pattern MOD_ID_PATTERN = Pattern.compile("[a-z][a-z0-9-_]{1,63}");
	private static final Object launcherSyncObject = new Object();

	private final boolean isDevelopment;
	private final Path gameDir;
	private final List<ModCandidateFinder> candidateFinders = new ArrayList<>();

	public ModResolver(QuiltLoaderImpl loader) {
		this.isDevelopment = loader.isDevelopmentEnvironment();
		this.gameDir = loader.getGameDir();
	}

	public ModResolver(boolean isDevelopment, Path gameDir) {
		this.isDevelopment = isDevelopment;
		this.gameDir = gameDir;
	}

	public void addCandidateFinder(ModCandidateFinder f) {
		candidateFinders.add(f);
	}

	private static String getReadablePath(Path gameDir, Path path) {
		Path relativized = path;
		if (gameDir != null) {
			gameDir = gameDir.normalize();

			if (path.startsWith(gameDir)) {
				relativized = gameDir.relativize(path);
			}
		}

		return readableNestedJarPaths2.getOrDefault(path.toString(), relativized.toString());
	}

	public static String getReadablePath(QuiltLoaderImpl loader, ModCandidate c) {
		return getReadablePath(loader.getGameDir(), c.getPath());
	}

	public String getReadablePath(Path path) {
		return getReadablePath(gameDir, path);
	}

	/** Only exposed for {@link ModSolver}. This is also intended to be temporary. */
	public static Path getSourcePath(Path originPath) {
		// FIXME: Make ModSolver use a different method to get the source URL from an original URL - perhaps via the ModCandidate?
		for (Map.Entry<String, List<Path>> entry : inMemoryCache.entrySet()) {
			for (Path path : entry.getValue()) {
				if (originPath.equals(path)) {
					return path;
				}
			}
		}
		return null;
	}

	/** @param errorList The list of errors. The returned list of errors all need to be prefixed with "it " in order to make sense. */
	public static boolean isModIdValid(String modId, List<String> errorList) {
		// A more useful error list for MOD_ID_PATTERN
		if (modId.isEmpty()) {
			errorList.add("is empty!");
			return false;
		}

		if (modId.length() == 1) {
			errorList.add("is only a single character! (It must be at least 2 characters long)!");
		} else if (modId.length() > 64) {
			errorList.add("has more than 64 characters!");
		}

		char first = modId.charAt(0);

		if (first < 'a' || first > 'z') {
			errorList.add("starts with an invalid character '" + first + "' (it must be a lowercase a-z - uppercase isn't allowed anywhere in the ID)");
		}

		Set<Character> invalidChars = null;

		for (int i = 1; i < modId.length(); i++) {
			char c = modId.charAt(i);

			if (c == '-' || c == '_' || ('0' <= c && c <= '9') || ('a' <= c && c <= 'z')) {
				continue;
			}

			if (invalidChars == null) {
				invalidChars = new HashSet<>();
			}

			invalidChars.add(c);
		}

		if (invalidChars != null) {
			StringBuilder error = new StringBuilder("contains invalid characters: ");
			error.append(invalidChars.stream().map(value -> "'" + value + "'").collect(Collectors.joining(", ")));
			errorList.add(error.append("!").toString());
		}

		assert errorList.isEmpty() == MOD_ID_PATTERN.matcher(modId).matches() : "Errors list " + errorList + " didn't match the mod ID pattern!";
		return errorList.isEmpty();
	}

	/** A "scanner" like class that tests a single URL (representing either the root of a jar file or a folder) to see
	 * if it contains a fabric.mod.json file, and as such if it can be loaded. Instances of this are created by
	 * {@link ModResolver#resolve(FabricLoader)} and recursively if the scanned mod contains other jar files. */
	@SuppressWarnings("serial")
	class ProcessAction extends RecursiveAction {
		private final Map<String, ModCandidateSet> candidatesById;
		private final Path path;
		private final int depth;
		private final boolean requiresRemap;

		ProcessAction(Map<String, ModCandidateSet> candidatesById, Path path, int depth, boolean requiresRemap) {
			this.candidatesById = candidatesById;
			this.path = path;
			this.depth = depth;
			this.requiresRemap = requiresRemap;
		}

		@Override
		protected void compute() {
			FileSystemUtil.FileSystemDelegate jarFs;
			final Path fabricModJson, quiltModJson, rootDir;

			Log.debug(LogCategory.RESOLUTION, "Testing " + path);

			if (Files.isDirectory(path)) {
				// Directory
				fabricModJson = path.resolve("fabric.mod.json");
				quiltModJson = path.resolve("quilt.mod.json");
				rootDir = path;

				if (isDevelopment && !Files.exists(fabricModJson) && !Files.exists(quiltModJson)) {
					Log.warn(LogCategory.RESOLUTION, "Adding directory " + path + " to mod classpath in development environment - workaround for Gradle splitting mods into two directories");
					synchronized (launcherSyncObject) {
						QuiltLauncher launcher = QuiltLauncherBase.getLauncher();
						if (launcher != null) {
							launcher.addToClassPath(path);
						}
					}
				}
			} else {
				// JAR file
				try {
					jarFs = FileSystemUtil.getJarFileSystem(path, false);
					fabricModJson = jarFs.get().getPath("fabric.mod.json");
					quiltModJson = jarFs.get().getPath("quilt.mod.json");
					rootDir = jarFs.get().getRootDirectories().iterator().next();
				} catch (IOException e) {
					throw new RuntimeException("Failed to open mod JAR at " + path + "!", e);
				} catch (ZipError e) {
					throw new RuntimeException("Jar at " + path + " is corrupted, please redownload it!");
				}
			}

			FabricLoaderModMetadata[] info;

			try {
				info = new FabricLoaderModMetadata[] { ModMetadataReader.read(quiltModJson).asFabricModMetadata() };
			} catch (ParseException e) {
				throw new RuntimeException(String.format("Mod at \"%s\" has an invalid quilt.mod.json file!", path), e);
			} catch (NoSuchFileException notQuilt) {

				try {
					info = new FabricLoaderModMetadata[] { FabricModMetadataReader.parseMetadata(fabricModJson) };
				} catch (ParseMetadataException.MissingField e){
					throw new RuntimeException(String.format("Mod at \"%s\" has an invalid fabric.mod.json file! The mod is missing the following required field!", path), e);
				} catch (ParseException | ParseMetadataException e) {
					throw new RuntimeException(String.format("Mod at \"%s\" has an invalid fabric.mod.json file!", path), e);
				} catch (NoSuchFileException e) {
					Log.warn(LogCategory.RESOLUTION, String.format("Neither a fabric nor a quilt JAR at \"%s\", ignoring", path));
					info = new FabricLoaderModMetadata[0];
				} catch (IOException e) {
					throw new RuntimeException(String.format("Failed to open fabric.mod.json for mod at \"%s\"!", path), e);
				} catch (Throwable t) {
					throw new RuntimeException(String.format("Failed to parse mod metadata for mod at \"%s\"", path), t);
				}

			} catch (IOException e) {
				throw new RuntimeException(String.format("Failed to open quilt.mod.json for mod at \"%s\"!", path), e);
			} catch (Throwable t) {
				throw new RuntimeException(String.format("Failed to parse mod metadata for mod at \"%s\"", path), t);
			}

			for (FabricLoaderModMetadata i : info) {
				ModCandidate candidate = new ModCandidate(i, path, depth, requiresRemap);
				boolean added;

				if (candidate.getInfo().getId() == null || candidate.getInfo().getId().isEmpty()) {
					throw new RuntimeException(String.format("Mod file `%s` has no id", path));
				}

				if (!MOD_ID_PATTERN.matcher(candidate.getInfo().getId()).matches()) {
					List<String> errorList = new ArrayList<>();
					isModIdValid(candidate.getInfo().getId(), errorList);
					StringBuilder fullError = new StringBuilder("Mod id `");
					fullError.append(candidate.getInfo().getId()).append("` does not match the requirements because");

					if (errorList.size() == 1) {
						fullError.append(" it ").append(errorList.get(0));
					} else {
						fullError.append(":");
						for (String error : errorList) {
							fullError.append("\n  - It ").append(error);
						}
					}

					throw new RuntimeException(fullError.toString());
				}

				for(ModProvided provides : candidate.getMetadata().provides()) {
					String id = provides.id;
					if (!MOD_ID_PATTERN.matcher(id).matches()) {
						List<String> errorList = new ArrayList<>();
						isModIdValid(id, errorList);
						StringBuilder fullError = new StringBuilder("Mod id provides `");
						fullError.append(id).append("` does not match the requirements because");

						if (errorList.size() == 1) {
							fullError.append(" it ").append(errorList.get(0));
						} else {
							fullError.append(":");
							for (String error : errorList) {
								fullError.append("\n  - It ").append(error);
							}
						}

						throw new RuntimeException(fullError.toString());
					}
				}

				added = candidatesById.computeIfAbsent(candidate.getInfo().getId(), ModCandidateSet::new).add(candidate);

				if (!added) {
					Log.debug(LogCategory.RESOLUTION, candidate.getPath() + " already present as " + candidate);
				} else {
					Log.debug(LogCategory.RESOLUTION, "Adding " + candidate.getPath() + " as " + candidate);

					List<Path> jarInJars = inMemoryCache.computeIfAbsent(candidate.getPath().toString(), (u) -> {
						Log.debug(LogCategory.RESOLUTION, "Searching for nested JARs in " + candidate);
						Log.debug(LogCategory.RESOLUTION, u);
						Collection<String> jars = candidate.getMetadata().jars();
						List<Path> list = new ArrayList<>(jars.size());

						jars.stream()
								.map((j) -> rootDir.resolve(j.replace("/", rootDir.getFileSystem().getSeparator())))
								.forEach((modPath) -> {
									if (!modPath.toString().endsWith(".jar")) {
										Log.warn(LogCategory.RESOLUTION, "Found nested jar entry that didn't end with '.jar': " + modPath);
										return;
									}

									if (Files.isDirectory(modPath)) {
										list.add(modPath);
									} else {
										// TODO: pre-check the JAR before loading it, if possible
										Log.debug(LogCategory.RESOLUTION, "Found nested JAR: " + modPath);
										Path dest = inMemoryFs.getPath(UUID.randomUUID() + ".jar");

										try {
											Files.copy(modPath, dest);
										} catch (IOException e) {
											throw new RuntimeException("Failed to load nested JAR " + modPath + " into memory (" + dest + ")!", e);
										}

										list.add(dest);

										Log.error(LogCategory.RESOLUTION, "SKIPPIGN ADDING READABLE PATH");
//											readableNestedJarPaths.put(UrlUtil.asUrl(dest).toString(), String.format("%s!%s", getReadablePath(candidate.getOriginUrl()), modPath));
									}
								});

						return list;
					});

					if (!jarInJars.isEmpty()) {
						invokeAll(
								jarInJars.stream()
										.map((p) -> new ProcessAction(candidatesById, p.normalize(), depth + 1, requiresRemap))
										.collect(Collectors.toList())
						);
					}
				}
			}

			/* if (jarFs != null) {
				jarFs.close();
			} */
		}
	}

	/** The main entry point for finding mods from both the classpath, the game provider, and the filesystem.
	 *
	 * @param loader The loader. If this is null then none of the builtin mods will be added. (Primarily useful during
	 *			tests).
	 * @return The final map of modids to the {@link ModCandidate} that should be used for that ID.
	 * @throws ModResolutionException if something entr wrong trying to find a valid set. */
	public ModSolveResult resolve(QuiltLoaderImpl loader) throws ModResolutionException {
		ConcurrentMap<String, ModCandidateSet> candidatesById = new ConcurrentHashMap<>();

		long time1 = System.currentTimeMillis();
		Queue<ProcessAction> allActions = new ConcurrentLinkedQueue<>();
		ForkJoinPool pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
		for (ModCandidateFinder f : candidateFinders) {
			f.findCandidates(loader, (path, requiresRemap) -> {
				ProcessAction action = new ProcessAction(candidatesById, path, 0, requiresRemap);
				allActions.add(action);
				pool.execute(action);
			});
		}

		// add builtin mods
		if (loader != null) {
			for (BuiltinMod mod : loader.getGameProvider().getBuiltinMods()) {
				addBuiltinMod(candidatesById, mod);
			}

			// Add the current Java version
			addBuiltinMod(candidatesById, new BuiltinMod(
					new File(System.getProperty("java.home")).toPath(),
					new BuiltinModMetadata.Builder("java", System.getProperty("java.specification.version").replaceFirst("^1\\.", ""))
							.setName(System.getProperty("java.vm.name"))
							.build()));
		}

		boolean tookTooLong = false;
		Throwable exception = null;
		try {
			pool.shutdown();
			// Comment out for debugging
			pool.awaitTermination(30, TimeUnit.SECONDS);
			for (ProcessAction action : allActions) {
				if (!action.isDone()) {
					tookTooLong = true;
				} else {
					Throwable t = action.getException();
					if (t != null) {
						if (exception == null) {
							exception = t;
						} else {
							exception.addSuppressed(t);
						}
					}
				}
			}
		} catch (InterruptedException e) {
			throw new ModResolutionException("Mod resolution took too long!", e);
		}
		if (tookTooLong) {
			throw new ModResolutionException("Mod resolution took too long!");
		}
		if (exception != null) {
			throw new ModResolutionException("Mod resolution failed!", exception);
		}

		long time2 = System.currentTimeMillis();
		ModSolver solver = new ModSolver();
		ModSolveResult result = solver.findCompatibleSet(candidatesById);

		long time3 = System.currentTimeMillis();
		Log.debug(LogCategory.RESOLUTION, "Mod resolution detection time: " + (time2 - time1) + "ms");
		Log.debug(LogCategory.RESOLUTION, "Mod resolution time: " + (time3 - time2) + "ms");

		for (ModCandidate candidate : result.modMap.values()) {
			candidate.getInfo().emitFormatWarnings();
		}

		return result;
	}

	private void addBuiltinMod(ConcurrentMap<String, ModCandidateSet> candidatesById, BuiltinMod mod) {
		candidatesById.computeIfAbsent(mod.metadata.getId(), ModCandidateSet::new)
				.add(new ModCandidate(new BuiltinMetadataWrapperFabric(mod.metadata), mod.path, 0, false));
	}

	public static FileSystem getInMemoryFs() {
		return inMemoryFs;
	}
}