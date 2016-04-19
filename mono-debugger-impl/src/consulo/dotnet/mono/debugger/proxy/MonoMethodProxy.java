/*
 * Copyright 2013-2016 must-be.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package consulo.dotnet.mono.debugger.proxy;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mustbe.consulo.RequiredReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import consulo.dotnet.debugger.proxy.DotNetLocalVariableProxy;
import consulo.dotnet.debugger.proxy.DotNetMethodParameterProxy;
import consulo.dotnet.debugger.proxy.DotNetMethodProxy;
import consulo.dotnet.debugger.proxy.DotNetStackFrameProxy;
import consulo.dotnet.debugger.proxy.DotNetThreadProxy;
import consulo.dotnet.debugger.proxy.DotNetThrowValueException;
import consulo.dotnet.debugger.proxy.DotNetTypeProxy;
import consulo.dotnet.debugger.proxy.value.DotNetValueProxy;
import consulo.dotnet.mono.debugger.breakpoint.MonoBreakpointUtil;
import mono.debugger.InvokeFlags;
import mono.debugger.LocalVariableMirror;
import mono.debugger.MethodMirror;
import mono.debugger.MethodParameterMirror;
import mono.debugger.ThreadMirror;
import mono.debugger.ThrowValueException;
import mono.debugger.Value;

/**
 * @author VISTALL
 * @since 18.04.2016
 */
public class MonoMethodProxy implements DotNetMethodProxy
{
	private MethodMirror myMethodMirror;

	public MonoMethodProxy(MethodMirror methodMirror)
	{
		myMethodMirror = methodMirror;
	}

	@Override
	public boolean isStatic()
	{
		return myMethodMirror.isStatic();
	}

	@NotNull
	@Override
	public DotNetTypeProxy getDeclarationType()
	{
		return MonoTypeProxy.of(myMethodMirror.declaringType());
	}

	@NotNull
	@Override
	public DotNetMethodParameterProxy[] getParameters()
	{
		MethodParameterMirror[] parameters = myMethodMirror.parameters();
		DotNetMethodParameterProxy[] proxies = new DotNetMethodParameterProxy[parameters.length];
		for(int i = 0; i < parameters.length; i++)
		{
			MethodParameterMirror parameter = parameters[i];
			proxies[i] = new MonoMethodParameterProxy(i, parameter);
		}
		return proxies;
	}

	@NotNull
	@Override
	public DotNetLocalVariableProxy[] getLocalVariables(@NotNull DotNetStackFrameProxy frameProxy)
	{
		MonoStackFrameProxy proxy = (MonoStackFrameProxy) frameProxy;

		LocalVariableMirror[] locals = myMethodMirror.locals(proxy.getFrameMirror().location().codeIndex());
		DotNetLocalVariableProxy[] proxies = new DotNetLocalVariableProxy[locals.length];
		for(int i = 0; i < locals.length; i++)
		{
			LocalVariableMirror local = locals[i];
			proxies[i] = new MonoLocalVariableProxy(local);
		}
		return proxies;
	}

	@Nullable
	@Override
	public DotNetValueProxy invoke(@NotNull DotNetThreadProxy threadProxy, @NotNull DotNetValueProxy thisObjectProxy, @NotNull DotNetValueProxy... arguments) throws DotNetThrowValueException
	{
		ThreadMirror thread = ((MonoThreadProxy) threadProxy).getThreadMirror();
		Value<?> thisObject = ((MonoValueProxyBase) thisObjectProxy).getMirror();

		Value[] values = new Value[arguments.length];
		for(int i = 0; i < arguments.length; i++)
		{
			DotNetValueProxy argument = arguments[i];
			values[i] = ((MonoValueProxyBase) argument).getMirror();
		}
		try
		{
			return MonoValueProxyUtil.wrap(myMethodMirror.invoke(thread, InvokeFlags.DISABLE_BREAKPOINTS, thisObject, values));
		}
		catch(ThrowValueException e)
		{
			throw new DotNetThrowValueException(MonoValueProxyUtil.wrap(e.getThrowExceptionValue()));
		}
	}

	@RequiredReadAction
	@Nullable
	@Override
	public PsiElement findExecutableElementFromDebugInfo(@NotNull Project project, int executableChildrenAtLineIndex)
	{
		return MonoBreakpointUtil.findExecutableElementFromDebugInfo(project, myMethodMirror.debugInfo(), executableChildrenAtLineIndex);
	}

	@NotNull
	@Override
	public String getName()
	{
		return myMethodMirror.name();
	}
}
