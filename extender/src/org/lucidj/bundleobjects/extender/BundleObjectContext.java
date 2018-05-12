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

import org.lucidj.api.bundleobjects.Validate;
import org.lucidj.api.bundleobjects.Invalidate;
import org.lucidj.api.bundleobjects.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.SynchronousBundleListener;
import org.osgi.framework.wiring.BundleWiring;

public class BundleObjectContext implements SynchronousBundleListener
{
    private final static Logger log = LoggerFactory.getLogger (BundleObjectContext.class);

    private BundleContext context;

    private Set<Object> registered_objects = new HashSet<> ();

    public BundleObjectContext (BundleContext context)
    {
        this.context = context;
        context.addBundleListener (this);
    }

    private void call_annotated (Class<? extends Annotation> annotation, Object obj, Object... args)
        throws Exception
    {
        for (Method m: obj.getClass ().getDeclaredMethods ())
        {
            if (m.isAnnotationPresent (annotation))
            {
                try
                {
                    m.setAccessible (true);
                    m.invoke (obj, args);
                }
                catch (InvocationTargetException e)
                {
                    if (e.getCause() instanceof Exception)
                    {
                        // Rethrow the actual exception received from the method
                        throw ((Exception)e.getCause());
                    }
                }
                catch (IllegalAccessException e)
                {
                    log.error ("Exception accessing @{} on {}: {}",
                        annotation.getSimpleName (), obj, e.getMessage ());
                }
                catch (IllegalArgumentException e)
                {
                    log.error ("Illegal arguments calling @{} on {}: {}",
                        annotation.getSimpleName (), obj, e.getMessage ());
                }
                catch (Throwable e)
                {
                    log.error ("Unhandled exception from @{} on {}: {}",
                        annotation.getSimpleName (), obj, e.getMessage ());
                }
                return;
            }
        }
    }

    public static <T> T get_proxy (Class<T> type)
    {
        Bundle bundle = FrameworkUtil.getBundle (type);
        BundleContext context = bundle.getBundleContext ();
        BundleWiring bundle_wiring = bundle.adapt (BundleWiring.class);
        ClassLoader classloader = bundle_wiring.getClassLoader();

        ServiceProxy handler = new ServiceProxy (context, type);
        Object proxy = Proxy.newProxyInstance (classloader, new Class[] { type }, handler);
        return (type.cast (proxy));
    }

    private void init_annotations (Object serviceObject)
    {
        //---------------------------
        // INIT ALL ANNOTATED FIELDS
        //---------------------------
        for (Field f: serviceObject.getClass ().getDeclaredFields ())
        {
            if (f.isAnnotationPresent (Service.class))
            {
                Object service = null;

                if (f.getType ().isAssignableFrom (BundleContext.class))
                {
                    service = context;
                }
                else // This must be an OSGi service
                {
                    service = get_proxy (f.getType ());
                }

                // Assign the service
                try
                {
                    f.setAccessible (true);
                    f.set (serviceObject, service);
                }
                catch (IllegalAccessException e)
                {
                    log.error ("Exception injecting @Service on {}", serviceObject, e);
                }
            }
        }
    }

    public void register (Object object)
    {
        // TODO: CHECK FOR BUNDLE STATUS
        log.info ("Registering BundleObject {}", object);
        registered_objects.add (object);
        init_annotations (object);
    }

    public void validate (Object object)
    {
        try
        {
            log.info ("Validate object {}", object);
            call_annotated (Validate.class, object);
        }
        catch (Throwable e)
        {
            log.error ("Exception while invoking @Validate method on {}", object, e);
        }
    }

    private void broadcast_event (int type, Object service_object)
    {
//        for (ServiceObject.Listener listener: listener_list)
//        {
//            listener.event (type, service_object);
//        }
    }

    private String get_state_string (int state)
    {
        switch (state)
        {
            case Bundle.INSTALLED:   return ("INSTALLED");
            case Bundle.RESOLVED:    return ("RESOLVED");
            case Bundle.STARTING:    return ("STARTING");
            case Bundle.STOPPING:    return ("STOPPING");
            case Bundle.ACTIVE:      return ("ACTIVE");
            case Bundle.UNINSTALLED: return ("UNINSTALLED");
        }
        return ("Unknown");
    }

    @Override // BundleListener
    public void bundleChanged (BundleEvent bundleEvent)
    {
        String msg = "Live long and prosper";
        Bundle bnd = bundleEvent.getBundle ();

        switch (bundleEvent.getType ())
        {
            case BundleEvent.INSTALLED:        msg = "INSTALLED";        break;
            case BundleEvent.LAZY_ACTIVATION:  msg = "LAZY_ACTIVATION";  break;
            case BundleEvent.RESOLVED:         msg = "RESOLVED";         break;
            case BundleEvent.STARTED:          msg = "STARTED";          break;
            case BundleEvent.STARTING:         msg = "STARTING";         break;
            case BundleEvent.STOPPED:          msg = "STOPPED";          break;
            case BundleEvent.STOPPING:         msg = "STOPPING";         break;
            case BundleEvent.UNINSTALLED:      msg = "UNINSTALLED";      break;
            case BundleEvent.UNRESOLVED:       msg = "UNRESOLVED";       break;
            case BundleEvent.UPDATED:          msg = "UPDATED";          break;
        }

        log.info ("------------>> bundleChanged: {} eventType={} state={}", bnd, msg, get_state_string (bnd.getState ()));

        if (!bundleEvent.getBundle ().equals (context.getBundle ()))
        {
            // Not us
            return;
        }

        if (bundleEvent.getType () == BundleEvent.STOPPING
            || bundleEvent.getType () == BundleEvent.UPDATED)
        {
            Set<Object> dying_objects = registered_objects;
            registered_objects = null;

            log.info ("Will invalidate {} objects", dying_objects.size ());

            for (Object object: dying_objects)
            {
                try
                {
                    log.info ("Invalidate object {}", object);
                    call_annotated (Invalidate.class, object);
                }
                catch (Throwable e)
                {
                    log.error ("Exception while invoking @Invalidate method on {}", object, e);
                }
            }
        }
    }

    public static class ServiceProxy implements InvocationHandler, AutoCloseable
    {
        private static final Boolean DEFAULT_BOOLEAN = Boolean.FALSE;
        private static final Byte DEFAULT_BYTE = new Byte ((byte) 0);
        private static final Short DEFAULT_SHORT = new Short((short) 0);
        private static final Integer DEFAULT_INT = new Integer(0);
        private static final Long DEFAULT_LONG = new Long(0);
        private static final Float DEFAULT_FLOAT = new Float(0.0f);
        private static final Double DEFAULT_DOUBLE = new Double(0.0);

        private final BundleContext context;
        private final Class service_class;

        public ServiceProxy (BundleContext context, Class service_class)
        {
            this.context = context;
            this.service_class = service_class;
        }

        @SuppressWarnings("unchecked")
        public Object invoke (Object proxy, Method method, Object[] args)
            throws Throwable
        {
            ServiceReference service_ref = null;
            Object service = null;

            try
            {
                if ((service_ref = context.getServiceReference (service_class)) != null)
                {
                    // We get and invoke the service, then unget on finnaly
                    service = context.getService (service_ref);
                    log.info ("Proxy {} method {} ({})", service, method, args);
                    return (method.invoke (service, args));
                }
            }
            catch (IllegalAccessException | InvocationTargetException e)
            {
                log.error ("Exception on invoke service {}", service, e);
            }
            finally
            {
                if (service != null)
                {
                    context.ungetService (service_ref);
                }
            }

            //------------------------------------------------------
            // WE ASSUME HERE EXCEPTION, INVALID OR MISSING SERVICE
            //------------------------------------------------------

            log.info ("Empty service for {}", service_class);

            Class returnType = method.getReturnType();

            if (Boolean.TYPE.equals (returnType))
            {
                return (DEFAULT_BOOLEAN);
            }
            else if (Byte.TYPE.equals (returnType))
            {
                return (DEFAULT_BYTE);
            }
            else if (Short.TYPE.equals (returnType))
            {
                return (DEFAULT_SHORT);
            }
            else if (Integer.TYPE.equals (returnType))
            {
                return (DEFAULT_INT);
            }
            else if (Long.TYPE.equals (returnType))
            {
                return (DEFAULT_LONG);
            }
            else if (Float.TYPE.equals (returnType))
            {
                return (DEFAULT_FLOAT);
            }
            else if (Double.TYPE.equals (returnType))
            {
                return (DEFAULT_DOUBLE);
            }
            else
            {
                // Every other object
                return (null);
            }
        }

        @Override
        public void close () throws Exception
        {
            // Try with resources
        }
    }
}

// EOF
