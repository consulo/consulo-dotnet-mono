/**
 * @author VISTALL
 * @since 05-Sep-22
 */
module consulo.dotnet.mono {
    // TODO remove in future
    requires java.desktop;

    requires consulo.dotnet.mono.debugger.impl;

    requires consulo.language.api;
    requires consulo.process.api;
    requires consulo.execution.api;

    requires consulo.dotnet.api;
    requires consulo.dotnet.psi.api;
    requires consulo.dotnet.psi.impl;
    requires mono.soft.debugging;
    requires consulo.execution.debug.api;
    requires consulo.dotnet.debugger.impl;
    requires consulo.dotnet.documentation.api;

    exports consulo.mono.dotnet.module.extension;
    exports consulo.mono.dotnet.sdk;
}