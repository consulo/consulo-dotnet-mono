package consulo.dotnet.mono.debugger.proxy;

import consulo.application.util.NullableLazyValue;
import consulo.component.util.pointer.Named;
import consulo.dotnet.debugger.proxy.DotNetTypeProxy;
import consulo.dotnet.debugger.proxy.DotNetVariableProxy;
import mono.debugger.MirrorWithIdAndName;
import mono.debugger.TypeMirror;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * @author VISTALL
 * @since 19.04.2016
 */
public abstract class MonoVariableProxyBase<T extends MirrorWithIdAndName> implements Named, DotNetVariableProxy
{
	private NullableLazyValue<DotNetTypeProxy> myTypeValue = NullableLazyValue.of(() -> MonoTypeProxy.of(fetchType()));
	protected T myMirror;

	public MonoVariableProxyBase(@Nonnull T mirror)
	{
		myMirror = mirror;
	}

	@Nullable
	protected abstract TypeMirror fetchType();

	@Nullable
	@Override
	public final DotNetTypeProxy getType()
	{
		return myTypeValue.getValue();
	}

	@Nonnull
	public T getMirror()
	{
		return myMirror;
	}

	@Override
	public boolean equals(Object obj)
	{
		return obj instanceof MonoVariableProxyBase && myMirror.equals(((MonoVariableProxyBase) obj).myMirror);
	}

	@Override
	public int hashCode()
	{
		return myMirror.hashCode();
	}

	@Nonnull
	@Override
	public String getName()
	{
		return myMirror.name();
	}
}
