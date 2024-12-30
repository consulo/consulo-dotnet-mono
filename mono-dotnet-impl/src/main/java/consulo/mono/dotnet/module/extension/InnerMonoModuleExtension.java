package consulo.mono.dotnet.module.extension;

import consulo.content.bundle.Sdk;
import consulo.module.content.layer.ModifiableModuleRootLayer;
import consulo.module.content.layer.ModuleRootLayer;
import consulo.module.content.layer.extension.ModuleExtensionBase;
import consulo.module.extension.ModuleExtensionWithSdk;
import consulo.module.extension.ModuleInheritableNamedPointer;
import consulo.virtualFileSystem.VirtualFile;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * @author VISTALL
 * @since 05.05.14
 */
public abstract class InnerMonoModuleExtension<T extends InnerMonoModuleExtension<T>> extends ModuleExtensionBase<T> implements ModuleExtensionWithSdk<T>
{
	private ModuleInheritableNamedPointer<Sdk> myPointer;

	protected Sdk myParentSdk;

	private Sdk myLazySdk;

	public InnerMonoModuleExtension(@Nonnull String id, @Nonnull ModuleRootLayer rootModel)
	{
		super(id, rootModel);
		myPointer = new DummyModuleInheritableNamedPointer<Sdk>()
		{
			@Override
			public Sdk get()
			{
				return InnerMonoModuleExtension.this.get();
			}
		};
	}

	private Sdk get()
	{
		MonoDotNetModuleExtension extension = myModuleRootLayer.getExtensionWithoutCheck(MonoDotNetModuleExtension.class);

		Sdk parentSdk = !extension.isEnabled() ? null : extension.getSdk();
		if(parentSdk != myParentSdk)
		{
			myLazySdk = null;
		}

		if(myLazySdk == null)
		{
			myParentSdk = parentSdk;
			if(myParentSdk == null)
			{
				return null;
			}
			myLazySdk = createSdk(myParentSdk.getHomeDirectory());
		}
		return myLazySdk;
	}

	protected void setEnabledImpl(boolean val)
	{
		myIsEnabled = val;
		if(val)
		{
			((ModifiableModuleRootLayer)myModuleRootLayer).addModuleExtensionSdkEntry(this);
		}
	}

	protected abstract Sdk createSdk(VirtualFile virtualFile);

	@Nonnull
	@Override
	public ModuleInheritableNamedPointer<Sdk> getInheritableSdk()
	{
		return myPointer;
	}

	@Nullable
	@Override
	public Sdk getSdk()
	{
		return getInheritableSdk().get();
	}

	@Nullable
	@Override
	public String getSdkName()
	{
		return getInheritableSdk().getName();
	}
}
