/*******************************************************************************
 * Copyright (c) 2009, 2018 Mountainminds GmbH & Co. KG and Contributors
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Evgeny Mandrikov - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.core.internal.analysis.filter;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

/**
 * Filters branches that Kotlin compiler generates for coroutines.
 */
public final class KotlinCoroutineFilter implements IFilter {

	static boolean isLastArgumentContinuation(final MethodNode methodNode) {
		final Type methodType = Type.getMethodType(methodNode.desc);
		final int lastArgument = methodType.getArgumentTypes().length - 1;
		return lastArgument >= 0 && "kotlin.coroutines.Continuation".equals(
				methodType.getArgumentTypes()[lastArgument].getClassName());
	}

	public void filter(final MethodNode methodNode,
			final IFilterContext context, final IFilterOutput output) {

		if (!KotlinGeneratedFilter.isKotlinClass(context)) {
			return;
		}

		if (!"invokeSuspend".equals(methodNode.name)) {
			return;
		}

		new Matcher().match(methodNode, output);

	}

	private static class Matcher extends AbstractMatcher {
		private void match(final MethodNode methodNode,
				final IFilterOutput output) {
			cursor = methodNode.instructions.getFirst();
			nextIsInvokeStatic("kotlin/coroutines/intrinsics/IntrinsicsKt",
					"getCOROUTINE_SUSPENDED");
			nextIsVar(Opcodes.ASTORE, "COROUTINE_SUSPENDED");
			nextIsVar(Opcodes.ALOAD, "this");
			nextIs(Opcodes.GETFIELD);
			nextIs(Opcodes.TABLESWITCH);
			if (cursor == null) {
				return;
			}
			final TableSwitchInsnNode s = (TableSwitchInsnNode) cursor;
			final List<AbstractInsnNode> ignore = new ArrayList<AbstractInsnNode>(
					s.labels.size() * 2);

			nextIs(Opcodes.ALOAD);
			nextIs(Opcodes.DUP);
			nextIsType(Opcodes.INSTANCEOF, "kotlin/Result$Failure");
			nextIs(Opcodes.IFEQ);
			nextIsType(Opcodes.CHECKCAST, "kotlin/Result$Failure");
			nextIs(Opcodes.GETFIELD);
			nextIs(Opcodes.ATHROW);
			nextIs(Opcodes.POP);

			if (cursor == null) {
				return;
			}
			ignore.add(s);
			ignore.add(cursor);

			int suspensionPoint = 1;
			for (AbstractInsnNode i = cursor; i != null
					&& suspensionPoint < s.labels.size(); i = i.getNext()) {
				cursor = i;
				nextIsVar(Opcodes.ALOAD, "COROUTINE_SUSPENDED");
				nextIs(Opcodes.IF_ACMPNE);
				if (cursor == null) {
					continue;
				}
				final AbstractInsnNode continuationAfterLoadedResult = skipNonOpcodes(
						(((JumpInsnNode) cursor)).label);
				nextIsVar(Opcodes.ALOAD, "COROUTINE_SUSPENDED");
				nextIs(Opcodes.ARETURN);
				if (cursor == null
						|| skipNonOpcodes(cursor.getNext()) != skipNonOpcodes(
								s.labels.get(suspensionPoint))) {
					continue;
				}

				for (AbstractInsnNode j = i; j != null; j = j.getNext()) {
					cursor = j;
					nextIs(Opcodes.ALOAD);
					nextIs(Opcodes.DUP);
					nextIsType(Opcodes.INSTANCEOF, "kotlin/Result$Failure");
					nextIs(Opcodes.IFEQ);
					nextIsType(Opcodes.CHECKCAST, "kotlin/Result$Failure");
					nextIs(Opcodes.GETFIELD);
					nextIs(Opcodes.ATHROW);
					nextIs(Opcodes.POP);

					nextIs(Opcodes.ALOAD);
					if (cursor != null && skipNonOpcodes(cursor
							.getNext()) == continuationAfterLoadedResult) {
						ignore.add(i);
						ignore.add(cursor);
						suspensionPoint++;
						break;
					}
				}
			}

			cursor = s.dflt;
			nextIsType(Opcodes.NEW, "java/lang/IllegalStateException");
			nextIs(Opcodes.DUP);
			nextIs(Opcodes.LDC);
			if (!((LdcInsnNode) cursor).cst.equals(
					"call to 'resume' before 'invoke' with coroutine")) {
				return;
			}
			nextIsInvokeSuper("java/lang/IllegalStateException",
					"(Ljava/lang/String;)V");
			nextIs(Opcodes.ATHROW);
			if (cursor == null) {
				return;
			}

			output.ignore(s.dflt, cursor);
			for (int i = 0; i < ignore.size(); i += 2) {
				output.ignore(ignore.get(i), ignore.get(i + 1));
			}
		}
	}

}
