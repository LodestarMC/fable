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

package org.quiltmc.loader.impl.metadata.qmj;

import org.quiltmc.loader.api.plugin.ModMetadataExt.ModEntrypoint;
import org.quiltmc.loader.impl.util.QuiltLoaderInternal;
import org.quiltmc.loader.impl.util.QuiltLoaderInternalType;

/**
 * Represents a class entry inside of that specifies a language adapter to use to load the class.
 */
@QuiltLoaderInternal(QuiltLoaderInternalType.LEGACY_EXPOSED)
public final class AdapterLoadableClassEntry implements ModEntrypoint {
	private final String adapter;
	private final String value;

	public AdapterLoadableClassEntry(String value) {
		this.adapter = "default";
		this.value = value;
	}

	public AdapterLoadableClassEntry(String adapter, String value) {
		this.adapter = adapter;
		this.value = value;
	}

	public String getAdapter() {
		return this.adapter;
	}

	public String getValue() {
		return this.value;
	}
}
