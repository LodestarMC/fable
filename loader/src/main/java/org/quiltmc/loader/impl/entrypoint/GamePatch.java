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

package org.quiltmc.loader.impl.entrypoint;

import org.objectweb.asm.ClassReader;
import org.quiltmc.loader.impl.launch.common.QuiltLauncher;
import org.quiltmc.loader.impl.util.QuiltLoaderInternal;
import org.quiltmc.loader.impl.util.QuiltLoaderInternalType;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.List;
import java.util.ListIterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@QuiltLoaderInternal(QuiltLoaderInternalType.LEGACY_EXPOSED)
public abstract class GamePatch {
	protected static ClassNode readClass(ClassReader reader) {
		if (reader == null) return null;

		ClassNode node = new ClassNode();
		reader.accept(node, 0);
		return node;
	}

	protected FieldNode findField(ClassNode node, Predicate<FieldNode> predicate) {
		return node.fields.stream().filter(predicate).findAny().orElse(null);
	}

	protected List<FieldNode> findFields(ClassNode node, Predicate<FieldNode> predicate) {
		return node.fields.stream().filter(predicate).collect(Collectors.toList());
	}

	protected MethodNode findMethod(ClassNode node, Predicate<MethodNode> predicate) {
		return node.methods.stream().filter(predicate).findAny().orElse(null);
	}

	protected AbstractInsnNode findInsn(MethodNode node, Predicate<AbstractInsnNode> predicate, boolean last) {
		if (last) {
			for (int i = node.instructions.size() - 1; i >= 0; i--) {
				AbstractInsnNode insn = node.instructions.get(i);

				if (predicate.test(insn)) {
					return insn;
				}
			}
		} else {
			for (int i = 0; i < node.instructions.size(); i++) {
				AbstractInsnNode insn = node.instructions.get(i);

				if (predicate.test(insn)) {
					return insn;
				}
			}
		}

		return null;
	}

	protected void moveAfter(ListIterator<AbstractInsnNode> it, int opcode) {
		while (it.hasNext()) {
			AbstractInsnNode node = it.next();

			if (node.getOpcode() == opcode) {
				break;
			}
		}
	}

	protected void moveBefore(ListIterator<AbstractInsnNode> it, int opcode) {
		moveAfter(it, opcode);
		it.previous();
	}

	protected void moveAfter(ListIterator<AbstractInsnNode> it, AbstractInsnNode targetNode) {
		while (it.hasNext()) {
			AbstractInsnNode node = it.next();

			if (node == targetNode) {
				break;
			}
		}
	}

	protected void moveBefore(ListIterator<AbstractInsnNode> it, AbstractInsnNode targetNode) {
		moveAfter(it, targetNode);
		it.previous();
	}

	protected void moveBeforeType(ListIterator<AbstractInsnNode> it, int nodeType) {
		while (it.hasPrevious()) {
			AbstractInsnNode node = it.previous();

			if (node.getType() == nodeType) {
				break;
			}
		}
	}

	protected boolean isStatic(int access) {
		return ((access & Opcodes.ACC_STATIC) != 0);
	}

	protected boolean isPublicStatic(int access) {
		return ((access & 0x0F) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
	}

	protected boolean isPublicInstance(int access) {
		//noinspection PointlessBitwiseExpression
		return ((access & 0x0F) == (Opcodes.ACC_PUBLIC | 0 /* non-static */));
	}

	public void process(QuiltLauncher launcher, Function<String, ClassReader> classSource, Consumer<ClassNode> classEmitter) {
		throw new AbstractMethodError(getClass() + " must override one of the 'process' methods!");
	}

	public void process(QuiltLauncher launcher, GamePatchContext context) {
		process(launcher, context::getClassSourceReader, context::addPatchedClass);
	}
}
