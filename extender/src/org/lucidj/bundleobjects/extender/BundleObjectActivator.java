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

import org.lucidj.api.bundleobjects.BundleObjectManager;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.weaving.WeavingHook;

public class BundleObjectActivator implements BundleActivator
{
    private final static Logger log = LoggerFactory.getLogger (BundleObjectActivator.class);

    private BundleObjectManagerImpl bom;
    private ServiceRegistration<BundleObjectManager> sr_bom;
    private ServiceRegistration<WeavingHook> sr_wh;

    @Override
    public void start (BundleContext context)
        throws Exception
    {
        BundleStartLevel bsl = context.getBundle ().adapt (BundleStartLevel.class);
        int bom_startlevel = bsl.getStartLevel();

        bom = new BundleObjectManagerImpl (context, bom_startlevel);

        sr_bom = context.registerService (BundleObjectManager.class, bom, null);
        sr_wh = context.registerService (WeavingHook.class, bom, null);
        log.info ("BundleObjects Manager started on level {}", bom_startlevel);
    }

    @Override
    public void stop (BundleContext context)
        throws Exception
    {
        sr_wh.unregister ();
        sr_bom.unregister ();
        log.info ("BundleObjects Manager stopped");
    }
}

// EOF
