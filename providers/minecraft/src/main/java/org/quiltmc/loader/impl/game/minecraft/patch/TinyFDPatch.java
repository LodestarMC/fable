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

package org.quiltmc.loader.impl.game.minecraft.patch;

import java.util.ListIterator;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.impl.entrypoint.GamePatch;
import org.quiltmc.loader.impl.entrypoint.GamePatchContext;
import org.quiltmc.loader.impl.launch.common.QuiltLauncher;

import net.fabricmc.api.EnvType;

/**
 * Patch the TinyFileDialogs.tinyfd_openFileDialog call to use a trusted string in MoreOptionsDialog.
 *
 * <p>This patch applies to Minecraft versions 20w21a -> 23w04a inclusive
 */
public final class TinyFDPatch extends GamePatch {
	private static final String MORE_OPTIONS_DIALOG_CLASS_NAME = "net.minecraft.class_5292";
	private static final String TINYFD_METHOD_NAME = "tinyfd_openFileDialog";
	// This is the en_us value of selectWorld.import_worldgen_settings.select_file
	private static final String DIALOG_TITLE = "Select settings file (.json)";

	@Override
	public void process(QuiltLauncher launcher, String namespace, GamePatchContext context) {
		if (launcher.getEnvironmentType() != EnvType.CLIENT) {
			// Fix should only be applied to clients.
			return;
		}

		String className = MORE_OPTIONS_DIALOG_CLASS_NAME;

		// Only remap the classname when needed to prevent loading the mappings when not required in prod.
		if (!launcher.getMappingConfiguration().getTargetNamespace().equals("intermediary")
				&& QuiltLoader.getMappingResolver().getNamespaces().contains("intermediary")) {
			className = QuiltLoader.getMappingResolver().mapClassName("intermediary", MORE_OPTIONS_DIALOG_CLASS_NAME);
		}

		final ClassNode classNode = context.getClassNode(className);

		if (classNode == null) {
			// Class is not present in this version, nothing to do.
			return;
		}

		patchMoreOptionsDialog(classNode);
		context.addPatchedClass(classNode);
	}

	private void patchMoreOptionsDialog(ClassNode classNode) {
		for (MethodNode method : classNode.methods) {
			final ListIterator<AbstractInsnNode> iterator = findTargetMethodNode(method);

			if (iterator == null) {
				continue;
			}

			while (iterator.hasPrevious()) {
				final AbstractInsnNode insnNode = iterator.previous();

				// Find the Text.getString() instruction
				// or find the TranslatableText.getString() instruction present in older versions (e.g 1.16.5)
				if (insnNode.getOpcode() == Opcodes.INVOKEINTERFACE
						|| insnNode.getOpcode() == Opcodes.INVOKEVIRTUAL) {
					InsnList insnList = new InsnList();
					// Drop the possibly malicious value
					insnList.add(new InsnNode(Opcodes.POP));
					// And replace it with something we trust
					insnList.add(new LdcInsnNode(DIALOG_TITLE));

					method.instructions.insert(insnNode, insnList);
					return;
				}
			}

			throw new IllegalStateException("Failed to patch MoreOptionsDialog");
		}

		// At this point we failed to find a valid target method.
		// 20w20a and 20w20b have the class but do not use tinyfd
	}

	private ListIterator<AbstractInsnNode> findTargetMethodNode(MethodNode methodNode) {
		if ((methodNode.access & Opcodes.ACC_SYNTHETIC) == 0) {
			// We know it's in a synthetic method
			return null;
		}

		// Visit all the instructions until we find the TinyFileDialogs.tinyfd_openFileDialog call
		ListIterator<AbstractInsnNode> iterator = methodNode.instructions.iterator();

		while (iterator.hasNext()) {
			final AbstractInsnNode instruction = iterator.next();

			if (instruction.getOpcode() != Opcodes.INVOKESTATIC) {
				continue;
			}

			if (!(instruction instanceof MethodInsnNode)) {
				continue;
			}

			final MethodInsnNode methodInsnNode = (MethodInsnNode) instruction;

			if (methodInsnNode.name.equals(TINYFD_METHOD_NAME)) {
				return iterator;
			}
		}

		return null;
	}
}
