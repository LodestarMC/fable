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

package net.fabricmc.loader;

import java.net.URL;

import net.fabricmc.loader.metadata.LoaderModMetadata;

/**
 * @deprecated Use {@link net.fabricmc.loader.api.ModContainer} instead
 */
@Deprecated
public abstract class ModContainer implements net.fabricmc.loader.api.ModContainer {
//	protected abstract List<Path> getCodeSourcePaths();
//
	public abstract URL getOriginUrl();

	public abstract LoaderModMetadata getInfo();
}
