package consulo.mono.dotnet.module.extension;

import consulo.annotation.component.ExtensionImpl;
import consulo.localize.LocalizeValue;
import consulo.module.content.layer.ModuleExtensionProvider;
import consulo.module.content.layer.ModuleRootLayer;
import consulo.module.extension.ModuleExtension;
import consulo.module.extension.MutableModuleExtension;
import consulo.mono.dotnet.icon.MonoDotNetIconGroup;
import consulo.ui.image.Image;

import javax.annotation.Nonnull;

/**
 * @author VISTALL
 * @since 05-Sep-22
 */
@ExtensionImpl
public class MonoDotNetModuleExtensionProvider implements ModuleExtensionProvider<MonoDotNetModuleExtension>
{
	@Nonnull
	@Override
	public String getId()
	{
		return "mono-dotnet";
	}

	@Nonnull
	@Override
	public LocalizeValue getName()
	{
		return LocalizeValue.localizeTODO("Mono");
	}

	@Nonnull
	@Override
	public Image getIcon()
	{
		return MonoDotNetIconGroup.mono();
	}

	@Nonnull
	@Override
	public ModuleExtension<MonoDotNetModuleExtension> createImmutableExtension(@Nonnull ModuleRootLayer moduleRootLayer)
	{
		return new MonoDotNetMutableModuleExtension(getId(), moduleRootLayer);
	}

	@Nonnull
	@Override
	public MutableModuleExtension<MonoDotNetModuleExtension> createMutableExtension(@Nonnull ModuleRootLayer moduleRootLayer)
	{
		return new MonoDotNetMutableModuleExtension(getId(), moduleRootLayer);
	}
}
