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

package net.fabricmc.loader.api.entrypoint;

import net.fabricmc.loader.api.ModContainer;

/**
 * A container holding both an entrypoint instance and the {@link ModContainer} which has provided the entrypoint.
 *
 * @param <T> The type of the entrypoint
 * @see net.fabricmc.loader.api.FabricLoader#getEntrypointContainers(String, Class)
 * @deprecated Please migrate to the quilt version: {@link org.quiltmc.loader.api.entrypoint.EntrypointContainer}. 
 */
@Deprecated
public interface EntrypointContainer<T> {
	/**
	 * Returns the entrypoint instance. It will be constructed the first time you call this method.
	 */
	T getEntrypoint();

	/**
	 * Returns the mod that provided this entrypoint.
	 */
	ModContainer getProvider();
}
