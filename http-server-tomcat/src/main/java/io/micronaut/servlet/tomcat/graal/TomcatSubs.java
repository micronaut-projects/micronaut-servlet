/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.tomcat.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.TypeHint;
import org.apache.catalina.*;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.loader.ParallelWebappClassLoader;
import org.apache.catalina.loader.WebappClassLoaderBase;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.coyote.UpgradeProtocol;
import org.apache.coyote.ajp.AjpAprProtocol;
import org.apache.coyote.ajp.AjpNio2Protocol;
import org.apache.coyote.ajp.AjpNioProtocol;
import org.apache.coyote.http11.Http11AprProtocol;
import org.apache.coyote.http11.Http11Nio2Protocol;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.tomcat.util.compat.JreCompat;
import org.apache.tomcat.util.modeler.NoDescriptorRegistry;
import org.apache.tomcat.util.modeler.Registry;

import java.security.ProtectionDomain;

//CHECKSTYLE:OFF

/**
 * Type hints and constants.
 */
@TypeHint({
        Constants.class,
        StandardContext.class,
        ErrorReportValve.class,
        ParallelWebappClassLoader.class,
        AccessLog.class,
        AsyncDispatcher.class,
        Authenticator.class,
        Cluster.class,
        Container.class,
        Contained.class,
        Context.class,
        CredentialHandler.class,
        DistributedManager.class,
        Engine.class,
        Executor.class,
        Group.class,
        Host.class,
        JmxEnabled.class,
        Lifecycle.class,
        LifecycleListener.class,
        Loader.class,
        Manager.class,
        Pipeline.class,
        Realm.class,
        Role.class,
        Server.class,
        Session.class,
        SessionIdGenerator.class,
        SessionListener.class,
        Store.class,
        StoreManager.class,
        User.class,
        UserDatabase.class,
        Valve.class,
        WebResource.class,
        WebResourceRoot.class,
        Wrapper.class,
        UpgradeProtocol.class
})
@Internal
final class Constants {
    static final NoDescriptorRegistry REGISTRY = new NoDescriptorRegistry();
}

/**
 * Internal class for Graal support.
 */
@Internal
@TargetClass(StandardRoot.class)
@TypeHint(typeNames = {
        "io.micronaut.servlet.tomcat.graal.Constants",
        "com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl",
        "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl",
        "org.apache.catalina.authenticator.jaspic.AuthConfigFactoryImpl",
        "org.apache.naming.factory.EjbFactory",
        "org.apache.naming.factory.ResourceEnvFactory",
        "org.apache.naming.factory.ResourceFactory",
        "org.apache.naming.factory.TransactionFactory"
})
final class Org_apache_catalina_webresources_StandardRoot {

    @Substitute
    protected void registerURLStreamHandlerFactory() {
        // noop
    }
}

/**
 * Internal class for Graal support.
 */
@Internal
@TargetClass(StandardContext.class)
@TypeHint(value = StandardContext.class, accessType = {
        TypeHint.AccessType.ALL_DECLARED_CONSTRUCTORS, TypeHint.AccessType.ALL_PUBLIC_METHODS})
final class Org_apache_catalina_core_StandardContext {
    @Substitute
    public boolean getDelegate() {
        return true;
    }
}

/**
 * Internal class for Graal support.
 */
@Internal
@TargetClass(JreCompat.class)
@TypeHint(value = {
        Http11AprProtocol.class,
        Http11Nio2Protocol.class,
        Http2Protocol.class,
        Http11NioProtocol.class,
        AjpNioProtocol.class,
        AjpNio2Protocol.class,
        AjpAprProtocol.class
}, accessType = {TypeHint.AccessType.ALL_DECLARED_CONSTRUCTORS, TypeHint.AccessType.ALL_PUBLIC_METHODS})
final class Org_apache_tomcat_util_compat_JreCompat {
    @Substitute
    public static boolean isGraalAvailable() {
        return true;
    }
}

/**
 * Internal class for Graal support.
 */
@Internal
@TargetClass(Registry.class)
final class Org_apache_tomcat_util_modeler_Registry {
    @Substitute
    public static synchronized Registry getRegistry(Object key, Object guard) {
        return Constants.REGISTRY;
    }
}

/**
 * Internal class for Graal support.
 */
@Internal
@TargetClass(WebappClassLoaderBase.class)
final class Org_apache_catalina_loader_WebappClassLoaderBase {
    @Substitute
    private void clearReferencesJdbc() {
        // no-op
    }

    @Substitute
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        return ClassLoader.getSystemClassLoader().loadClass(name);
    }

    @Substitute
    protected Class<?> findClassInternal(String name) {
        try {
            return ClassLoader.getSystemClassLoader().loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new NoClassDefFoundError(name);
        }
    }

    @Substitute
    protected final Class<?> doDefineClass(String name, byte[] b, int off, int len,
                                           ProtectionDomain protectionDomain) {
        try {
            return ClassLoader.getSystemClassLoader().loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new NoClassDefFoundError(name);
        }
    }
}

//CHECKSTYLE:ON
