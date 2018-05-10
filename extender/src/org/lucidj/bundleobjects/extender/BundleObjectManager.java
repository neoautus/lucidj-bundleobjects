/*
 * Copyright 2018 NEOautus Ltd. (http://neoautus.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.lucidj.bundleobjects.extender;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.LoaderClassPath;
import javassist.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lucidj.api.bundleobjects.BundleObject;

import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleWiring;

public class BundleObjectManager implements Observer, WeavingHook
{
    private final static Logger log = LoggerFactory.getLogger (BundleObjectManager.class);

    private final static String THIS_PACKAGE =
        BundleObjectManager.class.getPackage ().getName () + ".";
    private final static String BUNDLEOBJECT_CLASS_NAME =
        BundleObject.class.getName ();

    private final static String GLUE_REGISTER =
    "{" +
    "    org.osgi.framework.Bundle bundle = org.osgi.framework.FrameworkUtil.getBundle (this.getClass ());\n" +
    "    org.osgi.framework.BundleContext context = bundle.getBundleContext ();\n" +
    "    org.osgi.framework.ServiceReference service_ref = null;\n" +
    "    Object service = null;\n" +
    "    try {\n" +
    "        service_ref = context.getServiceReference (java.util.Observer.class);\n" +
    "        if (service_ref != null) {\n" +
    "            service = context.getService (service_ref);\n" +
    "            java.lang.reflect.Method m = java.util.Observer.class.getMethod (\"update\", new Class[] { java.util.Observable.class, Object.class });\n" +
    "            m.invoke (service, new Object[] { null, this });\n" +
    "        }\n" +
    "    }\n" +
    "    catch (Exception ignore) {}" +
    "    finally {\n" +
    "        if (service != null) {\n" +
    "            context.ungetService (service_ref);\n" +
    "        }\n" +
    "    }\n" +
    "}";

    private final static String GLUE_VALIDATE =
    "{" +
    "    org.osgi.framework.Bundle bundle = org.osgi.framework.FrameworkUtil.getBundle (this.getClass ());\n" +
    "    org.osgi.framework.BundleContext context = bundle.getBundleContext ();\n" +
    "    org.osgi.framework.ServiceReference service_ref = null;\n" +
    "    Object service = null;\n" +
    "    try {\n" +
    "        service_ref = context.getServiceReference (java.util.Observer.class);\n" +
    "        if (service_ref != null) {\n" +
    "            service = context.getService (service_ref);\n" +
    "            java.lang.reflect.Method m = java.util.Observer.class.getMethod (\"update\", new Class[] { java.util.Observable.class, Object.class });\n" +
    "            m.invoke (service, new Object[] { new java.util.Observable (), this });\n" +
    "        }\n" +
    "    }\n" +
    "    catch (Exception ignore) {}" +
    "    finally {\n" +
    "        if (service != null) {\n" +
    "            context.ungetService (service_ref);\n" +
    "        }\n" +
    "    }\n" +
    "}";

    private BundleContext context;
    private int bom_startlevel = 100;

    private final Map<BundleContext, BundleObjectContext> context_map = new ConcurrentHashMap<> ();

    public BundleObjectManager (BundleContext context)
    {
        this.context = context;
    }

    @Override
    public void weave (WovenClass wc)
    {
        BundleWiring bwg = wc.getBundleWiring ();
        Bundle bnd = bwg.getBundle ();

        if (bnd.getBundleId () == 0)
        {
            // No need to act on the Framework :)
            return;
        }

        BundleStartLevel bsl = bnd.adapt (BundleStartLevel.class);

        if (bsl.getStartLevel () < bom_startlevel)
        {
            // We only act on bundles with startlevel >=  defined startlevel
            return;
        }

        String class_name = wc.getClassName ();

        if (class_name.startsWith (THIS_PACKAGE)
            || class_name.equals (BUNDLEOBJECT_CLASS_NAME))
        {
            return;
        }

        ClassLoader bcl = bwg.getClassLoader ();

        try
        {
            bcl.loadClass (BUNDLEOBJECT_CLASS_NAME);
        }
        catch (ClassNotFoundException e)
        {
            // The bundle is not wired to BundleObject
            return;
        }

        log.info ("Verifying class {} from {}", class_name, bnd);

        // Add the source classloader for the woven class
        ClassPool cp = new ClassPool (true);
        cp.insertClassPath (new LoaderClassPath (BundleObjectManager.class.getClassLoader ()));
        cp.insertClassPath (new LoaderClassPath (bwg.getClassLoader ()));
        CtClass cc;
        Object ann;

        try
        {
            cc = cp.get (class_name);

            if (cc.isEnum () || cc.isAnnotation () || cc.isInterface ())
            {
                return;
            }

            if ((ann = cc.getAnnotation (BundleObject.class)) == null)
            {
                return;
            }
        }
        catch (ClassNotFoundException | NotFoundException e)
        {
            log.warn ("BundleObject class {} not found", class_name);
            return;
        }

        log.info ("Weaving @BundleObject class {}", class_name);

        try
        {
            CtConstructor[] ccts =  cc.getDeclaredConstructors ();

            for (CtConstructor cct: ccts)
            {
                if (!cct.isClassInitializer ())
                {
                    try
                    {
                        cct.insertBeforeBody (GLUE_REGISTER);
                        cct.insertAfter (GLUE_VALIDATE);
                    }
                    catch (javassist.CannotCompileException e)
                    {
                        log.error ("Exception instrumenting class {}", class_name, e);
                    }
                }
            }

            // Write back the shiny new bytecode
            wc.setBytes (cc.toBytecode ());
            cc.detach ();
        }
        catch (Exception e)
        {
            log.error ("Exception weaving: {}", class_name, e);
        }
    }

    @Override // Observer
    public void update (Observable o, Object arg)
    {
        Bundle bo_bnd = FrameworkUtil.getBundle (arg.getClass ());
        BundleContext bo_ctx = bo_bnd.getBundleContext ();
        BundleObjectContext boc = null;

        synchronized (context_map)
        {
            if ((boc = context_map.get (bo_ctx)) == null)
            {
                boc = new BundleObjectContext (bo_ctx);
                context_map.put (bo_ctx, boc);
            }
        }

        if (o == null)
        {
            boc.register (arg);
        }
        else
        {
            boc.validate (arg);
        }
    }
}

// EOF
