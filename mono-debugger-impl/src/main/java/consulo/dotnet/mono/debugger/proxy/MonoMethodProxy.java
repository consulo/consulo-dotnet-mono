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

import consulo.annotation.access.RequiredReadAction;
import consulo.dotnet.debugger.proxy.*;
import consulo.dotnet.debugger.proxy.value.DotNetValueProxy;
import consulo.dotnet.mono.debugger.breakpoint.MonoBreakpointUtil;
import consulo.language.psi.PsiElement;
import consulo.project.Project;
import mono.debugger.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

	@Override
	public boolean isAbstract()
	{
		return myMethodMirror.isAbstract();
	}

	@Override
	public boolean isAnnotatedBy(@Nonnull String attributeVmQName)
	{
		for(CustomAttributeMirror customAttributeMirror : myMethodMirror.customAttributes())
		{
			MethodMirror constructorMirror = customAttributeMirror.getConstructorMirror();
			TypeMirror typeMirror = constructorMirror.declaringType();
			if(attributeVmQName.equals(typeMirror.fullName()))
			{
				return true;
			}
		}
		return false;
	}

	@Nonnull
	@Override
	public DotNetTypeProxy getDeclarationType()
	{
		return MonoTypeProxy.of(myMethodMirror.declaringType());
	}

	@Nonnull
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

	@Nonnull
	@Override
	public DotNetLocalVariableProxy[] getLocalVariables(@Nonnull DotNetStackFrameProxy frameProxy)
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
	public DotNetValueProxy invoke(@Nonnull DotNetStackFrameProxy frameProxy,
			@Nullable DotNetValueProxy thisObjectProxy,
			@Nonnull DotNetValueProxy... arguments) throws DotNetThrowValueException, DotNetNotSuspendedException
	{
		return invokeAdvanced(frameProxy, thisObjectProxy, arguments).getResult();
	}

	@Nonnull
	@Override
	public DotNetMethodInvokeResult invokeAdvanced(@Nonnull DotNetStackFrameProxy frameProxy,
												   @Nullable DotNetValueProxy thisObjectProxy,
												   @Nonnull DotNetValueProxy... arguments) throws DotNetThrowValueException, DotNetNotSuspendedException
	{
		ThreadMirror thread = ((MonoThreadProxy) frameProxy.getThread()).getThreadMirror();
		Value<?> thisObject = thisObjectProxy == null ? null : ((MonoValueProxyBase) thisObjectProxy).getMirror();

		Value[] values = new Value[arguments.length];
		for(int i = 0; i < arguments.length; i++)
		{
			DotNetValueProxy argument = arguments[i];
			values[i] = ((MonoValueProxyBase) argument).getMirror();
		}
		try
		{
			InvokeResult result = myMethodMirror.invokeNew(thread, InvokeFlags.pack(InvokeFlags.DISABLE_BREAKPOINTS, InvokeFlags.OUT_THIS), thisObject, values);
			return new DotNetMethodInvokeResult(MonoValueProxyUtil.wrap(result.getValue()), MonoValueProxyUtil.wrap(result.getOutThis()));
		}
		catch(NotSuspendedException e)
		{
			throw new DotNetNotSuspendedException(e);
		}
		catch(ThrowValueException e)
		{
			throw new DotNetThrowValueException(frameProxy, MonoValueProxyUtil.wrap(e.getThrowExceptionValue()));
		}
	}

	@RequiredReadAction
	@Nullable
	@Override
	public PsiElement findExecutableElementFromDebugInfo(@Nonnull Project project, int executableChildrenAtLineIndex)
	{
		return MonoBreakpointUtil.findExecutableElementFromDebugInfo(project, myMethodMirror.debugInfo(), executableChildrenAtLineIndex);
	}

	@Nonnull
	@Override
	public String getName()
	{
		return myMethodMirror.name();
	}
}
