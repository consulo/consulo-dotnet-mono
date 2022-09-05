/**
 * @author VISTALL
 * @since 05-Sep-22
 */
module consulo.dotnet.mono.debugger.impl {
    requires mono.soft.debugging;
    requires org.jooq.joou;

    requires consulo.dotnet.psi.api;
    requires consulo.dotnet.execution.api;
    requires consulo.dotnet.api;
    requires consulo.dotnet.debugger.api;
    requires consulo.dotnet.debugger.impl;

    exports consulo.dotnet.mono.debugger to consulo.dotnet.mono;

    opens consulo.dotnet.mono.debugger to consulo.proxy;
}