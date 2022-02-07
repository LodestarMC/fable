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

package net.fabricmc.loader.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.objectweb.asm.Opcodes;

import net.fabricmc.accesswidener.AccessWidener;
import net.fabricmc.accesswidener.AccessWidenerReader;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.impl.discovery.ArgumentModCandidateFinder;
import net.fabricmc.loader.impl.discovery.ClasspathModCandidateFinder;
import net.fabricmc.loader.impl.discovery.DirectoryModCandidateFinder;
import net.fabricmc.loader.impl.discovery.ModCandidate;
import net.fabricmc.loader.impl.discovery.ModDiscoverer;
import net.fabricmc.loader.impl.discovery.ModResolutionException;
import net.fabricmc.loader.impl.discovery.ModResolver;
import net.fabricmc.loader.impl.discovery.RuntimeModRemapper;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.knot.Knot;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.util.DefaultLanguageAdapter;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

@SuppressWarnings("deprecation")
public final class FabricLoaderImpl extends net.fabricmc.loader.FabricLoader {
	public static final FabricLoaderImpl INSTANCE = InitHelper.get();

	public static final int ASM_VERSION = Opcodes.ASM9;

	public static final String VERSION = "dev";
	public static final String MOD_ID = "quilt_loader";
	public static final String DEFAULT_MODS_DIR = "mods";
	public static final String DEFAULT_CONFIG_DIR = "config";

	protected final Map<String, ModContainerImpl> modMap = new HashMap<>();
	protected List<ModContainerImpl> mods = new ArrayList<>();
	public static final String MOD_ID = "quilt_loader";

	public static final String CACHE_DIR_NAME = ".fabric"; // relative to game dir
	private static final String PROCESSED_MODS_DIR_NAME = "processedMods"; // relative to cache dir
	public static final String REMAPPED_JARS_DIR_NAME = "remappedJars"; // relative to cache dir
	private static final String TMP_DIR_NAME = "tmp"; // relative to cache dir

	protected final Map<String, ModContainerImpl> modMap = new HashMap<>();
	private List<ModCandidate> modCandidates;
	protected List<ModContainerImpl> mods = new ArrayList<>();

	private final Map<String, LanguageAdapter> adapterMap = new HashMap<>();
	private final EntrypointStorage entrypointStorage = new EntrypointStorage();
	private final AccessWidener accessWidener = new AccessWidener();

	private final ObjectShare objectShare = new ObjectShareImpl();

	private boolean frozen = false;

	private Object gameInstance;

	private MappingResolver mappingResolver;
	private GameProvider provider;
	private Path gameDir;
	private Path configDir;
	private Path modsDir;

	protected QuiltLoaderImpl() {
	}

	/**
	 * Freeze the FabricLoader, preventing additional mods from being loaded.
	 */
	public void freeze() {
		if (frozen) {
			throw new IllegalStateException("Already frozen!");
		}

		frozen = true;
		finishModLoading();
	}

	public GameProvider getGameProvider() {
		if (provider == null) throw new IllegalStateException("game provider not set (yet)");

		return provider;
	}

	public GameProvider tryGetGameProvider() {
		return provider;
	}

	public void setGameProvider(GameProvider provider) {
		this.provider = provider;

		setGameDir(provider.getLaunchDirectory());
	}

	private void setGameDir(Path gameDir) {
		this.gameDir = gameDir;
		String configDir = System.getProperty(SystemProperties.CONFIG_DIRECTORY);
		this.configDir = gameDir.resolve((configDir == null || configDir.isEmpty()) ? DEFAULT_CONFIG_DIR : configDir);
		initializeModsDir(gameDir);
	}

	private void initializeModsDir(Path gameDir) {
		String modsDir = System.getProperty(SystemProperties.MODS_DIRECTORY);
		this.modsDir = gameDir.resolve((modsDir == null || modsDir.isEmpty()) ? DEFAULT_MODS_DIR : modsDir);
	}

	public Object getGameInstance() {
		return gameInstance;
	}

	public EnvType getEnvironmentType() {
		return QuiltLauncherBase.getLauncher().getEnvironmentType();
	}

	/**
	 * @return The game instance's root directory.
	 */
	@Override
	public Path getGameDir() {
		return gameDir;
	}

	/**
	 * @return The game instance's configuration directory.
	 */
	public Path getConfigDir() {
		if (configDir == null) {
			// May be null during tests
			// If this is in production then things are about to go very wrong.
			return null;
		}

		if (!Files.exists(configDir)) {
			try {
				Files.createDirectories(configDir);
			} catch (IOException e) {
				throw new RuntimeException(String.format("Failed to create config directory at '%s'", configDir), e);
			}
		}

		return configDir;
	}

	public Path getModsDir() {
		// modsDir should be initialized before this method is ever called, this acts as a very special failsafe
		if (modsDir == null) {
			initializeModsDir(gameDir);
		}

		if (!Files.exists(modsDir)) {
			try {
				Files.createDirectories(modsDir);
			} catch (IOException e) {
				throw new RuntimeException(String.format("Failed to create mods directory at '%s'", modsDir), e);
			}
		}
		return modsDir;
	}

	public void load() {
		if (provider == null) throw new IllegalStateException("game provider not set");
		if (frozen) throw new IllegalStateException("Frozen - cannot load additional mods!");

		try {
			setup();
		} catch (ModResolutionException exception) {
			throw new FormattedException("Incompatible mod set!", exception);
		}
	}

	private void setup() throws ModResolutionException {
		ModResolver resolver = new ModResolver(this);
		resolver.addCandidateFinder(new ClasspathModCandidateFinder());
		resolver.addCandidateFinder(new ArgumentModCandidateFinder(isDevelopmentEnvironment()));
		resolver.addCandidateFinder(new DirectoryModCandidateFinder(getModsDir(), isDevelopmentEnvironment()));
		ModSolveResult result = resolver.resolve(this);
		Map<String, ModCandidate> candidateMap = result.modMap;
		modCandidates = new ArrayList<>(candidateMap.values());
		// dump mod list

		StringBuilder modListText = new StringBuilder();

		for (ModCandidate mod : modCandidates) {
			if (modListText.length() > 0) modListText.append('\n');

			modListText.append("\t- ");
			modListText.append(mod.getId());
			modListText.append(' ');
			modListText.append(mod.getVersion().raw());
// TODO
//			if (!mod.getParentMods().isEmpty()) {
//				modListText.append(" via ");
//				modListText.append(mod.getParentMods().iterator().next().getId());
//			}
		}

		int count = modCandidates.size();
		Log.info(LogCategory.GENERAL, "Loading %d mod%s:%n%s", count, count != 1 ? "s" : "", modListText);

		if (DependencyOverrides.INSTANCE.getDependencyOverrides().size() > 0) {
			Log.info(LogCategory.GENERAL, "Dependencies overridden for \"%s\"", String.join(", ", DependencyOverrides.INSTANCE.getDependencyOverrides()));
		}

		Path cacheDir = gameDir.resolve(CACHE_DIR_NAME);
		Path outputdir = cacheDir.resolve(PROCESSED_MODS_DIR_NAME);

		// runtime mod remapping

		if (isDevelopmentEnvironment()) {
			if (System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE) == null) {
				Log.warn(LogCategory.MOD_REMAP, "Runtime mod remapping disabled due to no fabric.remapClasspathFile being specified. You may need to update loom.");
			} else {
				RuntimeModRemapper.remap(modCandidates, ModResolver.getInMemoryFs());
			}
		}

		// shuffle mods in-dev to reduce the risk of false order reliance, apply late load requests

		if (isDevelopmentEnvironment() && System.getProperty(SystemProperties.DEBUG_DISABLE_MOD_SHUFFLE) == null) {
			Collections.shuffle(modCandidates);
		}

		String modsToLoadLate = System.getProperty(SystemProperties.DEBUG_LOAD_LATE);

		if (modsToLoadLate != null) {
			for (String modId : modsToLoadLate.split(",")) {
				for (Iterator<ModCandidate> it = modCandidates.iterator(); it.hasNext(); ) {
					ModCandidate mod = it.next();

					if (mod.getId().equals(modId)) {
						it.remove();
						modCandidates.add(mod);
						break;
					}
				}
			}
		}

		// add mods

		for (ModCandidate mod : modCandidates) {
//			if (!mod.hasPath() && !mod.isBuiltin()) {
//				try {
//					mod.setPaths(Collections.singletonList(mod.copyToDir(outputdir, false)));
//				} catch (IOException e) {
//					throw new RuntimeException("Error extracting mod "+mod, e);
//				}
//			}

			addMod(mod);
		}

		//modCandidates = null;
	}

	protected void finishModLoading() {
		// add mods to classpath
		// TODO: This can probably be made safer, but that's a long-term goal
		for (ModContainerImpl mod : mods) {
			if (!mod.metadata().id().equals(MOD_ID) && !mod.getInfo().getType().equals("builtin")) {
				QuiltLauncherBase.getLauncher().addToClassPath(mod.getOriginPath());
			}
		}

		if (isDevelopmentEnvironment()) {
			// Many development environments will provide classes and resources as separate directories to the classpath.
			// As such, we're adding them to the classpath here and now.
			// To avoid tripping loader-side checks, we also don't add URLs already in modsList.
			// TODO: Perhaps a better solution would be to add the Sources of all parsed entrypoints. But this will do, for now.

			Set<Path> knownModPaths = new HashSet<>();

			for (ModContainerImpl mod : mods) {
				knownModPaths.add(mod.getOriginPath().toAbsolutePath().normalize());
			}

			// suppress fabric loader explicitly in case its fabric.mod.json is in a different folder from the classes
			Path fabricLoaderPath = ClasspathModCandidateFinder.getLoaderPath();
			if (fabricLoaderPath != null) knownModPaths.add(fabricLoaderPath.toAbsolutePath().normalize());

			for (String pathName : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
				if (pathName.isEmpty() || pathName.endsWith("*")) continue;

				Path path = Paths.get(pathName).toAbsolutePath().normalize();

				if (Files.isDirectory(path) && knownModPaths.add(path)) {
					QuiltLauncherBase.getLauncher().addToClassPath(path);
				}
			}
		}

		postprocessModMetadata();
		setupLanguageAdapters();
		setupMods();
	}

	public boolean hasEntrypoints(String key) {
		return entrypointStorage.hasEntrypoints(key);
	}

	@Override
	public <T> List<T> getEntrypoints(String key, Class<T> type) {
		return entrypointStorage.getEntrypoints(key, type);
	}

	@Override
	public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
		return entrypointStorage.getEntrypointContainers(key, type);
	}

	@Override
	public MappingResolver getMappingResolver() {
		if (mappingResolver == null) {
			mappingResolver = new QuiltMappingResolver(
				QuiltLauncherBase.getLauncher().getMappingConfiguration()::getMappings,
				QuiltLauncherBase.getLauncher().getTargetNamespace()
			);
		}

		return mappingResolver;
	}

	public Optional<org.quiltmc.loader.api.ModContainer> getModContainer(String id) {


		return Optional.ofNullable(modMap.get(id));
	}

	// TODO: add to QuiltLoader api
	public ObjectShare getObjectShare() {
		return objectShare;
	}

	public ModCandidate getModCandidate(String id) {
		if (modCandidates == null) return null;

		for (ModCandidate mod : modCandidates) {
			if (mod.getId().equals(id)) return mod;
		}

		return null;
	}
	public Collection<org.quiltmc.loader.api.ModContainer> getAllMods() {
		return Collections.unmodifiableList(mods);
	}

	public boolean isModLoaded(String id) {
		return modMap.containsKey(id);
	}

	public boolean isDevelopmentEnvironment() {
		QuiltLauncher launcher = QuiltLauncherBase.getLauncher();
		if (launcher == null) {
			// Most likely a test
			return true;
		}
		return launcher.isDevelopment();
	}

	/**
	 * @return A list of all loaded mods, as ModContainers.
	 * @deprecated Use {@link net.fabricmc.loader.api.FabricLoader#getAllMods()}
	 */
	@Deprecated
	public Collection<ModContainerImpl> getModContainers() {
		return Collections.unmodifiableList(mods);
	}

	@Deprecated
	public List<ModContainerImpl> getMods() {
		return Collections.unmodifiableList(mods);
	}

	protected void addMod(ModCandidate candidate) throws ModResolutionException {
		InternalModMetadata meta = candidate.getMetadata();
		File origin = candidate.getPath().toFile();
		if (modMap.containsKey(meta.id())) {
			throw new ModSolvingError("Duplicate mod ID: " + meta.id() + "! (" + modMap.get(meta.id()).getOriginPath().toFile() + ", " + origin + ")");
		}

		if (!meta.environment().matches(getEnvironmentType())) {
			return;
		}

		ModContainerImpl container = new ModContainerImpl(meta, candidate.getPath());
		mods.add(container);
		modMap.put(meta.id(), container);

		for (ModProvided provided : meta.provides()) {
			if (modMap.containsKey(provided.id)) {
				throw new ModSolvingError("Duplicate provided alias: " + provided + "! (" + modMap.get(meta.id()).getOriginPath().toFile() + ", " + origin + ")");
			}

			modMap.put(provided.id, container);
		}
	}

	protected void postprocessModMetadata() {
		for (ModContainerImpl mod : mods) {
			if (!(mod.getInfo().getVersion() instanceof SemanticVersion)) {
				Log.warn(LogCategory.METADATA, "Mod `%s` (%s) does not respect SemVer - comparison support is limited.",
						mod.getInfo().getId(), mod.getInfo().getVersion().getFriendlyString());
			} else if (((SemanticVersion) mod.getInfo().getVersion()).getVersionComponentCount() >= 4) {
				Log.warn(LogCategory.METADATA, "Mod `%s` (%s) uses more dot-separated version components than SemVer allows; support for this is currently not guaranteed.",
						mod.getInfo().getId(), mod.getInfo().getVersion().getFriendlyString());
			}
		}
	}

	private void setupLanguageAdapters() {
		adapterMap.put("default", DefaultLanguageAdapter.INSTANCE);

		for (ModContainerImpl mod : mods) {
			// add language adapters
			for (Map.Entry<String, String> laEntry : mod.getInternalMeta().languageAdapters().entrySet()) {
				if (adapterMap.containsKey(laEntry.getKey())) {
					throw new RuntimeException("Duplicate language adapter key: " + laEntry.getKey() + "! (" + laEntry.getValue() + ", " + adapterMap.get(laEntry.getKey()).getClass().getName() + ")");
				}

				try {
					adapterMap.put(laEntry.getKey(), (LanguageAdapter) Class.forName(laEntry.getValue(), true, QuiltLauncherBase.getLauncher().getTargetClassLoader()).getDeclaredConstructor().newInstance());
				} catch (Exception e) {
					throw new RuntimeException("Failed to instantiate language adapter: " + laEntry.getKey(), e);
				}
			}
		}
	}

	private void setupMods() {
		for (ModContainerImpl mod : mods) {
			try {
				for (String in : mod.getInfo().getOldInitializers()) {
					String adapter = mod.getInfo().getOldStyleLanguageAdapter();
					entrypointStorage.addDeprecated(mod, adapter, in);
				}

				for (Map.Entry<String, Collection<AdapterLoadableClassEntry>> entry : mod.getInternalMeta().getEntrypoints().entrySet()) {
					for (AdapterLoadableClassEntry e : entry.getValue()) {
						entrypointStorage.add(mod, entry.getKey(), e, adapterMap);
					}
				}
			} catch (Exception e) {
				throw new RuntimeException(String.format("Failed to setup mod %s (%s)", mod.getInfo().getName(), mod.getOrigin()), e);
			}
		}
	}

	public void loadAccessWideners() {
		AccessWidenerReader accessWidenerReader = new AccessWidenerReader(accessWidener);

		for (ModContainerImpl mod : mods) {
			for (String accessWidener : mod.getInternalMeta().accessWideners()) {

				Path path = mod.getPath(accessWidener);

				try (BufferedReader reader = Files.newBufferedReader(path)) {
					accessWidenerReader.read(reader, getMappingResolver().getCurrentRuntimeNamespace());
				} catch (Exception e) {
					throw new RuntimeException("Failed to read accessWidener file from mod " + mod.getInternalMeta().id(), e);
				}
			}
		}
	}

	public void prepareModInit(Path newRunDir, Object gameInstance) {
		if (!frozen) {
			throw new RuntimeException("Cannot instantiate mods when not frozen!");
		}

		if (gameInstance != null && QuiltLauncherBase.getLauncher() instanceof Knot) {
			ClassLoader gameClassLoader = gameInstance.getClass().getClassLoader();
			ClassLoader targetClassLoader = QuiltLauncherBase.getLauncher().getTargetClassLoader();
			boolean matchesKnot = (gameClassLoader == targetClassLoader);
			boolean containsKnot = false;

			if (matchesKnot) {
				containsKnot = true;
			} else {
				gameClassLoader = gameClassLoader.getParent();

				while (gameClassLoader != null && gameClassLoader.getParent() != gameClassLoader) {
					if (gameClassLoader == targetClassLoader) {
						containsKnot = true;
					}

					gameClassLoader = gameClassLoader.getParent();
				}
			}

			if (!matchesKnot) {
				if (containsKnot) {
					Log.info(LogCategory.KNOT, "Environment: Target class loader is parent of game class loader.");
				} else {
					Log.warn(LogCategory.KNOT, "\n\n* CLASS LOADER MISMATCH! THIS IS VERY BAD AND WILL PROBABLY CAUSE WEIRD ISSUES! *\n"
							+ " - Expected game class loader: %s\n"
							+ " - Actual game class loader: %s\n"
							+ "Could not find the expected class loader in game class loader parents!\n",
							QuiltLauncherBase.getLauncher().getTargetClassLoader(), gameClassLoader);
				}
			}
		}

		this.gameInstance = gameInstance;

		if (gameDir != null) {
			try {
				if (!gameDir.toRealPath().equals(newRunDir.toRealPath())) {
					Log.warn(LogCategory.GENERAL, "Inconsistent game execution directories: engine says %s, while initializer says %s...",
							newRunDir.toRealPath(), gameDir.toRealPath());
					setGameDir(newRunDir);
				}
			} catch (IOException e) {
				Log.warn(LogCategory.GENERAL, "Exception while checking game execution directory consistency!", e);
			}
		} else {
			setGameDir(newRunDir);
		}
	}

	public AccessWidener getAccessWidener() {
		return accessWidener;
	}

	/**
	 * Sets the game instance. This is only used in 20w22a+ by the dedicated server and should not be called by anything else.
	 */
	@Deprecated
	public void setGameInstance(Object gameInstance) {
		if (this.getEnvironmentType() != EnvType.SERVER) {
			throw new UnsupportedOperationException("Cannot set game instance on a client!");
		}

		if (this.gameInstance != null) {
			throw new UnsupportedOperationException("Cannot overwrite current game instance!");
		}

		this.gameInstance = gameInstance;
	}

	public String[] getLaunchArguments(boolean sanitize) {
		return getGameProvider().getLaunchArguments(sanitize);
	}

	/**
	 * Provides singleton for static init assignment regardless of load order.
	 */
	public static class InitHelper {
		private static FabricLoaderImpl instance;

		public static FabricLoaderImpl get() {
			if (instance == null) instance = new FabricLoaderImpl();

			return instance;
		}
	}
}