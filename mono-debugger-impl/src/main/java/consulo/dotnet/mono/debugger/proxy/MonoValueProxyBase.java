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

import consulo.application.util.NullableLazyValue;
import consulo.dotnet.debugger.proxy.DotNetTypeProxy;
import consulo.dotnet.debugger.proxy.value.DotNetValueProxy;
import mono.debugger.MirrorWithId;
import mono.debugger.Value;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * @author VISTALL
 * @since 18.04.2016
 */
public abstract class MonoValueProxyBase<T extends Value<?>> implements DotNetValueProxy
{
	protected T myValue;

	private final NullableLazyValue<DotNetTypeProxy> myTypeValue = NullableLazyValue.of(() -> MonoTypeProxy.of(myValue::type));
	private final NullableLazyValue<Object> myValueValue = NullableLazyValue.of(() -> myValue.value());

	public MonoValueProxyBase(T value)
	{
		myValue = value;
	}

	public T getMirror()
	{
		return myValue;
	}

	@Override
	public boolean isEqualTo(@Nonnull DotNetValueProxy proxy)
	{
		if(proxy instanceof MonoValueProxyBase)
		{
			Value<?> value = ((MonoValueProxyBase<?>) proxy).myValue;
			if(value instanceof MirrorWithId && myValue instanceof MirrorWithId)
			{
				return ((MirrorWithId) value).id() == ((MirrorWithId) myValue).id();
			}
		}
		return false;
	}

	@Nullable
	@Override
	public DotNetTypeProxy getType()
	{
		return myTypeValue.getValue();
	}

	@Nonnull
	@Override
	public Object getValue()
	{
		return myValueValue.getValue();
	}
}
