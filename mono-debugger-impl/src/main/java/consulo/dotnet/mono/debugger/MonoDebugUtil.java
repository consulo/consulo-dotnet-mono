package consulo.dotnet.mono.debugger;

import com.intellij.openapi.project.Project;
import consulo.annotation.access.RequiredReadAction;
import consulo.dotnet.debugger.DotNetDebugContext;
import consulo.dotnet.debugger.DotNetDebuggerUtil;
import consulo.dotnet.psi.DotNetTypeDeclaration;
import consulo.dotnet.resolve.DotNetPsiSearcher;
import mono.debugger.TypeMirror;

import javax.annotation.Nonnull;

/**
 * @author VISTALL
 * @since 19.04.2016
 */
public class MonoDebugUtil
{
	@Nonnull
	@RequiredReadAction
	public static DotNetTypeDeclaration[] findTypesByQualifiedName(@Nonnull TypeMirror typeMirror, @Nonnull DotNetDebugContext debugContext)
	{
		Project project = debugContext.getProject();
		return DotNetPsiSearcher.getInstance(project).findTypes(DotNetDebuggerUtil.getVmQName(typeMirror.fullName()), debugContext.getResolveScope());
	}
}
