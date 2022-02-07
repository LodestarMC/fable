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


import org.quiltmc.loader.api.Version;
import org.quiltmc.loader.impl.metadata.FabricLoaderModMetadata;
import org.quiltmc.loader.impl.metadata.qmj.InternalModMetadata;

import java.nio.file.Path;

public class ModCandidate {
	private final FabricLoaderModMetadata info;
	private final Path origin;
	private final int depth;
	private final boolean requiresRemap;

	public ModCandidate(FabricLoaderModMetadata info, Path origin, int depth, boolean requiresRemap) {
		this.info = info;
		this.origin = origin;
		this.depth = depth;
		this.requiresRemap = requiresRemap;
	}

	public Path getPath() {
		return origin;
	}

	@Deprecated
	public FabricLoaderModMetadata getInfo() {
		return info;
	}

	public InternalModMetadata getMetadata() {
		return info.asQuiltModMetadata();
	}

	public String getId() {
		return info.getId();
	}

	public Version getVersion() {
		return getMetadata().version();
	}
	public int getDepth() {
		return depth;
	}

	public boolean requiresRemap() {
		return requiresRemap;
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof ModCandidate)) {
			return false;
		} else {
			ModCandidate other = (ModCandidate) obj;
			return other.info.getVersion().getFriendlyString().equals(info.getVersion().getFriendlyString()) && other.info.getId().equals(info.getId());
		}
	}

	@Override
	public int hashCode() {
		return info.getId().hashCode() * 17 + info.getVersion().hashCode();
	}

	@Override
	public String toString() {
		return "ModCandidate{" + info.getId() + "@" + info.getVersion().getFriendlyString() + "}";
	}
}