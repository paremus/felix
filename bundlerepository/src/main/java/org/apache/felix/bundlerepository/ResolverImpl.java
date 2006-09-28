/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.bundlerepository;

import java.net.URL;
import java.util.*;

import org.apache.felix.bundlerepository.LocalRepositoryImpl.LocalResourceImpl;
import org.osgi.framework.*;
import org.osgi.service.obr.*;

public class ResolverImpl implements Resolver
{
    private BundleContext m_context = null;
    private RepositoryAdmin m_admin = null;
    private LocalRepositoryImpl m_local = null;
    private Set m_addedSet = new HashSet();
    private Set m_resolveSet = new HashSet();
    private Set m_requiredSet = new HashSet();
    private Set m_optionalSet = new HashSet();
    private Map m_reasonMap = new HashMap();
    private Map m_unsatisfiedMap = new HashMap();
    private boolean m_resolved = false;

    public ResolverImpl(BundleContext context, RepositoryAdmin admin)
    {
        m_context = context;
        m_admin = admin;
    }

    public synchronized void add(Resource resource)
    {
        m_resolved = false;
        m_addedSet.add(resource);
    }

    public synchronized Requirement[] getUnsatisfiedRequirements()
    {
        if (m_resolved)
        {
            return (Requirement[])
                m_unsatisfiedMap.keySet().toArray(
                    new Requirement[m_unsatisfiedMap.size()]);
        }
        throw new IllegalStateException("The resources have not been resolved.");
    }

    public synchronized Resource[] getOptionalResources()
    {
        if (m_resolved)
        {
            return (Resource[])
                m_optionalSet.toArray(
                    new Resource[m_optionalSet.size()]);
        }
        throw new IllegalStateException("The resources have not been resolved.");
    }

    public synchronized Requirement[] getReason(Resource resource)
    {
        if (m_resolved)
        {
            return (Requirement[]) m_reasonMap.get(resource);
        }
        throw new IllegalStateException("The resources have not been resolved.");
    }

    public synchronized Resource[] getResources(Requirement requirement)
    {
        if (m_resolved)
        {
            return (Resource[]) m_unsatisfiedMap.get(requirement);
        }
        throw new IllegalStateException("The resources have not been resolved.");
    }

    public synchronized Resource[] getRequiredResources()
    {
        if (m_resolved)
        {
            return (Resource[])
                m_requiredSet.toArray(
                    new Resource[m_requiredSet.size()]);
        }
        throw new IllegalStateException("The resources have not been resolved.");
    }

    public synchronized Resource[] getAddedResources()
    {
        return (Resource[]) m_addedSet.toArray(new Resource[m_addedSet.size()]);
    }

    public synchronized boolean resolve()
    {
        // Get a current local repository.
        // TODO: OBR - We might want to make a smarter local repository
        // that caches installed bundles rather than re-parsing them
        // each time, since this could be costly.
        if (m_local != null)
        {
            m_local.dispose();
        }
        m_local = new LocalRepositoryImpl(m_context);

        // Reset instance values.
        m_resolveSet.clear();
        m_requiredSet.clear();
        m_optionalSet.clear();
        m_reasonMap.clear();
        m_unsatisfiedMap.clear();
        m_resolved = true;

        boolean result = true;

        // Loop through each resource in added list and resolve.
        for (Iterator iter = m_addedSet.iterator(); iter.hasNext(); )
        {
            if (!resolve((Resource) iter.next()))
            {
                // If any resource does not resolve, then the
                // entire result will be false.
                result = false;
            }
        }

        // Clean up the resulting data structures.
        List locals = Arrays.asList(m_local.getResources());
        m_requiredSet.removeAll(m_addedSet);
        m_requiredSet.removeAll(locals);
        m_optionalSet.removeAll(m_addedSet);
        m_optionalSet.removeAll(m_requiredSet);
        m_optionalSet.removeAll(locals);

        // Return final result.
        return result;
    }

    private boolean resolve(Resource resource)
    {
        boolean result = true;

        // Check for a cycle.
        if (m_resolveSet.contains(resource))
        {
            return result;
        }

        // Add to resolve map to avoid cycles.
        m_resolveSet.add(resource);

        // Resolve the requirements for the resource according to the
        // search order of: added, local, resolving, and remote resources.
        Requirement[] reqs = resource.getRequirements();
        if (reqs != null)
        {
            Resource candidate = null;
            for (int reqIdx = 0; reqIdx < reqs.length; reqIdx++)
            {
                candidate = searchAddedResources(reqs[reqIdx]);
                if (candidate == null)
                {
                    candidate = searchLocalResources(reqs[reqIdx]);
                    if (candidate == null)
                    {
                        candidate = searchResolvingResources(reqs[reqIdx]);
                        if (candidate == null)
                        {
                            candidate = searchRemoteResources(reqs[reqIdx]);
                        }
                    }
                }

                if ((candidate == null) && !reqs[reqIdx].isOptional())
                {
                    // The resolve failed.
                    result = false;
                    // Associated the current resource to the requirement
                    // in the unsatisfied requirement map.
                    Resource[] resources = (Resource[]) m_unsatisfiedMap.get(reqs[reqIdx]);
                    if (resources == null)
                    {
                        resources = new Resource[] { resource };
                    }
                    else
                    {
                        Resource[] tmp = new Resource[resources.length + 1];
                        System.arraycopy(resources, 0, tmp, 0, resources.length);
                        tmp[resources.length] = resource;
                        resources = tmp;
                    }
                    m_unsatisfiedMap.put(reqs[reqIdx], resources);
                }
                else if (candidate != null)
                {
                    // The resolved succeeded; record the candidate
                    // as either optional or required.
                    if (reqs[reqIdx].isOptional())
                    {
                        m_optionalSet.add(candidate);
                    }
                    else
                    {
                        m_requiredSet.add(candidate);
                    }

                    // Add the reason why the candidate was selected.
                    addReason(candidate, reqs[reqIdx]);

                    // Try to resolve the candidate.
                    if (!resolve(candidate))
                    {
                        result = false;
                    }
                }
            }
        }

        return result;
    }

    private Resource searchAddedResources(Requirement req)
    {
        for (Iterator iter = m_addedSet.iterator(); iter.hasNext(); )
        {
            Resource resource = (Resource) iter.next();
            Capability[] caps = resource.getCapabilities();
            for (int capIdx = 0; (caps != null) && (capIdx < caps.length); capIdx++)
            {
                if (req.isSatisfied(caps[capIdx]))
                {
                    // The requirement is already satisfied an existing
                    // resource, return the resource.
                    return resource;
                }
            }
        }

        return null;
    }

    private Resource searchLocalResources(Requirement req)
    {
        Resource[] resources = m_local.getResources();
        for (int resIdx = 0; (resources != null) && (resIdx < resources.length); resIdx++)
        {
            Capability[] caps = resources[resIdx].getCapabilities();
            for (int capIdx = 0; (caps != null) && (capIdx < caps.length); capIdx++)
            {
                if (req.isSatisfied(caps[capIdx]))
                {
                    return resources[resIdx];
                }
            }
        }

        return null;
    }

    private Resource searchResolvingResources(Requirement req)
    {
        Resource[] resources = (Resource[])
            m_resolveSet.toArray(new Resource[m_resolveSet.size()]);
        for (int resIdx = 0; (resources != null) && (resIdx < resources.length); resIdx++)
        {
            Capability[] caps = resources[resIdx].getCapabilities();
            for (int capIdx = 0; (caps != null) && (capIdx < caps.length); capIdx++)
            {
                if (req.isSatisfied(caps[capIdx]))
                {
                    return resources[resIdx];
                }
            }
        }

        return null;
    }

    private Resource searchRemoteResources(Requirement req)
    {
        // For now, guess that if there is a version associated with
        // the candidate capability that we should choose the highest
        // version; otherwise, choose the resource with the greatest
        // number of capabilities.
        // TODO: OBR - This could probably be improved.
        Resource best = null;
        Version bestVersion = null;
        Repository[] repos = m_admin.listRepositories();
        for (int repoIdx = 0; (repos != null) && (repoIdx < repos.length); repoIdx++)
        {
            Resource[] resources = repos[repoIdx].getResources();
            for (int resIdx = 0; (resources != null) && (resIdx < resources.length); resIdx++)
            {
                Capability[] caps = resources[resIdx].getCapabilities();
                for (int capIdx = 0; (caps != null) && (capIdx < caps.length); capIdx++)
                {
                    if (req.isSatisfied(caps[capIdx]))
                    {
                        if (best == null)
                        {
                            best = resources[resIdx];
                            Object v = caps[capIdx].getProperties().get(Resource.VERSION);
                            if ((v != null) && (v instanceof Version))
                            {
                                bestVersion = (Version) v;
                            }
                        }
                        else
                        {
                            Object v = caps[capIdx].getProperties().get(Resource.VERSION);

                            // If there is no version, then select the resource
                            // with the greatest number of capabilities.
                            if ((v == null) && (bestVersion == null)
                                && (best.getCapabilities().length < caps.length))
                            {
                                best = resources[resIdx];
                                bestVersion = (Version) v;
                            }
                            else if ((v != null) && (v instanceof Version))
                            {
                                // If there is no best version or if the current
                                // resource's version is lower, then select it.
                                if ((bestVersion == null) || (bestVersion.compareTo(v) < 0))
                                {
                                    best = resources[resIdx];
                                    bestVersion = (Version) v;
                                }
                                // If the current resource version is equal to the
                                // best, then select the one with the greatest
                                // number of capabilities.
                                else if ((bestVersion != null) && (bestVersion.compareTo(v) == 0)
                                    && (best.getCapabilities().length < caps.length))
                                {
                                    best = resources[resIdx];
                                    bestVersion = (Version) v;
                                }
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    public synchronized void deploy(boolean start)
    {
        // Must resolve if not already resolved.
        if (!m_resolved && !resolve())
        {
            // TODO: OBR - Use logger if possible.
            System.err.println("Resolver: Cannot resolve target resources.");
            return;
        }

        // Check to make sure that our local state cache is up-to-date
        // and error if it is not. This is not completely safe, because
        // the state can still change during the operation, but we will
        // be optimistic. This could also be made smarter so that it checks
        // to see if the local state changes overlap with the resolver.
        if (m_local.getLastModified() != m_local.getCurrentTimeStamp())
        {
            throw new IllegalStateException("Framework state has changed, must resolve again.");
        }

        // Eliminate duplicates from target, required, optional resources.
        Map deployMap = new HashMap();
        Resource[] resources = getAddedResources();
        for (int i = 0; (resources != null) && (i < resources.length); i++)
        {
            deployMap.put(resources[i], resources[i]);
        }
        resources = getRequiredResources();
        for (int i = 0; (resources != null) && (i < resources.length); i++)
        {
            deployMap.put(resources[i], resources[i]);
        }
        resources = getOptionalResources();
        for (int i = 0; (resources != null) && (i < resources.length); i++)
        {
            deployMap.put(resources[i], resources[i]);
        }
        Resource[] deployResources = (Resource[])
            deployMap.keySet().toArray(new Resource[deployMap.size()]);

        // List to hold all resources to be started.
        List startList = new ArrayList();

        // Deploy each resource, which will involve either finding a locally
        // installed resource to update or the installation of a new version
        // of the resource to be deployed.
        for (int i = 0; i < deployResources.length; i++)
        {
            // For the resource being deployed, see if there is an older
            // version of the resource already installed that can potentially
            // be updated.
            LocalRepositoryImpl.LocalResourceImpl localResource =
                findUpdatableLocalResource(deployResources[i]);
            // If a potentially updatable older version was found,
            // then verify that updating the local resource will not
            // break any of the requirements of any of the other
            // resources being deployed.
            if ((localResource != null) &&
                isResourceUpdatable(localResource, deployResources[i], deployResources))
            {
                // Only update if it is a different version.
                if (!localResource.equals(deployResources[i]))
                {
                    // Update the installed bundle.
                    try
                    {
                        localResource.getBundle().update(deployResources[i].getURL().openStream());
    
                        // If necessary, save the updated bundle to be
                        // started later.
                        if (start)
                        {
                            startList.add(localResource.getBundle());
                        }
                    }
                    catch (Exception ex)
                    {
                        // TODO: OBR - Use logger if possible.
                        System.err.println("Resolver: Update error - " + Util.getBundleName(localResource.getBundle()));
                        ex.printStackTrace(System.err);
                        return;
                    }
                }
            }
            else
            {
                // Install the bundle.
                try
                {
                    // Perform the install, but do not use the actual
                    // bundle JAR URL for the bundle location, since this will
                    // limit OBR's ability to manipulate bundle versions. Instead,
                    // use a unique timestamp as the bundle location.
                    URL url = deployResources[i].getURL();
                    if (url != null)
                    {
                        Bundle bundle = m_context.installBundle(
                            "obr://"
                            + deployResources[i].getSymbolicName()
                            + "/" + System.currentTimeMillis(),
                            url.openStream());
    
                        // If necessary, save the installed bundle to be
                        // started later.
                        if (start)
                        {
                            startList.add(bundle);
                        }
                    }
                }
                catch (Exception ex)
                {
                    // TODO: OBR - Use logger if possible.
                    System.err.println("Resolver: Install error - "
                        + deployResources[i].getSymbolicName());
                    ex.printStackTrace(System.err);
                    return;
                }
            }
        }

        for (int i = 0; i < startList.size(); i++)
        {
            try
            {
                ((Bundle) startList.get(i)).start();
            }
            catch (BundleException ex)
            {
                // TODO: OBR - Use logger if possible.
                System.err.println("Resolver: Start error - " + ex);
            }
        }
    }

    private void addReason(Resource resource, Requirement req)
    {
        Requirement[] reasons = (Requirement[]) m_reasonMap.get(resource);
        if (reasons == null)
        {
            reasons = new Requirement[] { req };
        }
        else
        {
            Requirement[] tmp = new Requirement[reasons.length + 1];
            System.arraycopy(reasons, 0, tmp, 0, reasons.length);
            tmp[reasons.length] = req;
            reasons = tmp;
        }
        m_reasonMap.put(resource, reasons);
    }

    // TODO: OBR - Think about this again and make sure that deployment ordering
    // won't impact it...we need to update the local state too.
    private LocalResourceImpl findUpdatableLocalResource(Resource resource)
    {
        // Determine if any other versions of the specified resource
        // already installed.
        Resource[] localResources = findLocalResources(resource.getSymbolicName());
        if (localResources != null)
        {
            // Since there are local resources with the same symbolic
            // name installed, then we must determine if we can
            // update an existing resource or if we must install
            // another one. Loop through all local resources with same
            // symbolic name and find the first one that can be updated
            // without breaking constraints of existing local resources.
            for (int i = 0; i < localResources.length; i++)
            {
                if (isResourceUpdatable(localResources[i], resource, m_local.getResources()))
                {
                    return (LocalResourceImpl) localResources[i];
                }
            }
        }
        return null;
    }

    private Resource[] findLocalResources(String symName)
    {
        Resource[] localResources = m_local.getResources();

        List matchList = new ArrayList();
        for (int i = 0; i < localResources.length; i++)
        {
            String localSymName = localResources[i].getSymbolicName();
            if ((localSymName != null) && localSymName.equals(symName))
            {
                matchList.add(localResources[i]);
            }
        }
        return (Resource[]) matchList.toArray(new Resource[matchList.size()]);
    }

    private boolean isResourceUpdatable(
        Resource oldVersion, Resource newVersion, Resource[] resources)
    {
        // Get all of the local resolvable requirements for the old
        // version of the resource from the specified resource array.
        Requirement[] reqs = getResolvableRequirements(oldVersion, resources);

        // Now make sure that all of the requirements resolved by the
        // old version of the resource can also be resolved by the new
        // version of the resource.
        Capability[] caps = newVersion.getCapabilities();
        if (caps == null)
        {
            return false;
        }
        for (int reqIdx = 0; reqIdx < reqs.length; reqIdx++)
        {
            boolean satisfied = false;
            for (int capIdx = 0; !satisfied && (capIdx < caps.length); capIdx++)
            {
                if (reqs[reqIdx].isSatisfied(caps[capIdx]))
                {
                    satisfied = true;
                }
            }

            // If any of the previously resolved requirements cannot
            // be resolved, then the resource is not updatable.
            if (!satisfied)
            {
                return false;
            }
        }

        return true;
    }
    
    private Requirement[] getResolvableRequirements(Resource resource, Resource[] resources)
    {
        // For the specified resource, find all requirements that are
        // satisfied by any of its capabilities in the specified resource
        // array.
        Capability[] caps = resource.getCapabilities();
        if ((caps != null) && (caps.length > 0))
        {
            List reqList = new ArrayList();
            for (int capIdx = 0; capIdx < caps.length; capIdx++)
            {
                boolean added = false;
                for (int resIdx = 0; !added && (resIdx < resources.length); resIdx++)
                {
                    Requirement[] reqs = resources[resIdx].getRequirements();
                    for (int reqIdx = 0;
                        (reqs != null) && (reqIdx < reqs.length);
                        reqIdx++)
                    {
                        if (reqs[reqIdx].isSatisfied(caps[capIdx]))
                        {
                            added = true;
                            reqList.add(reqs[reqIdx]);
                        }
                    }
                }
            }
            return (Requirement[])
                reqList.toArray(new Requirement[reqList.size()]);
        }
        return null;
    }
}