/*
 * Supplies the JDI VirtualMachineManager used by java-debug-core to launch/attach the
 * debuggee. The stock JDK manager (com.sun.jdi.Bootstrap.virtualMachineManager()) is all
 * that is needed for a plain `mainClass + classPaths` launch: its LaunchingConnector
 * injects -agentlib:jdwp, forks the debuggee JVM and returns an attached VirtualMachine.
 */
package dev.jcode.javadap;

import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachineManager;

public class JCodeVirtualMachineManagerProvider implements IVirtualMachineManagerProvider {

    private VirtualMachineManager vmManager;

    @Override
    public VirtualMachineManager getVirtualMachineManager() {
        if (vmManager == null) {
            vmManager = Bootstrap.virtualMachineManager();
        }
        return vmManager;
    }
}
