/*
 * Fable - quilt-/fabric-loader fork; <https://github.com/steviegt6/fable>
 * Copyright (C) 2016  FabricMC
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

package net.fabricmc.loader.impl.entrypoint;

import java.util.function.Consumer;

import org.quiltmc.loader.api.QuiltLoader;

@Deprecated
public class EntrypointUtils {

	public static <T> void invoke(String name, Class<T> type, Consumer<? super T> invoker) {
		try {
			org.quiltmc.loader.impl.entrypoint.EntrypointUtils.invoke(name, type, invoker);
		} catch (org.quiltmc.loader.api.entrypoint.EntrypointException e) {
			throw new net.fabricmc.loader.api.EntrypointException((QuiltLoader) null, e);
		}
	}
}
