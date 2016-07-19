/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.phenotips.termrequester;

import java.io.IOException;

import java.util.Collection;
import java.util.List;

import org.phenotips.termrequester.db.DatabaseService;
import org.phenotips.termrequester.github.GithubAPI;
import org.phenotips.termrequester.github.GithubAPIFactory;

import com.google.common.base.Optional;
import com.google.inject.Inject;


/**
 * Implements a phenotype manager, tying together the various backend components of the term requester.
 *
 * @version $Id$
 */
public class PhenotypeManagerImpl implements PhenotypeManager
{
    /**
     * The github api factory.
     */
    private GithubAPIFactory factory;

    /**
     * The database service.
     */
    private DatabaseService db;

    /**
     * The github connection.
     */
    private GithubAPI github;

    /**
     * CTOR.
     * @param factory the injected github api factory
     * @param db the database service
     */
    @Inject
    public PhenotypeManagerImpl(GithubAPIFactory factory, DatabaseService db)
    {
        this.factory = factory;
        this.db = db;
    }

    @Override
    public void init(GithubAPI.Repository repo)
    {
        github = factory.create(repo);
    }

    @Override
    public Phenotype createRequest(String name, Collection<String> synonyms, Optional<String> parentId,
                                   Optional<String> description) throws TermRequesterBackendException
    {
        Phenotype pt = new Phenotype(name, description.or(""));
        pt.addAllSynonyms(synonyms);
        /* TODO USE PROPER DEFAULT PARENT */
        Phenotype parent = db.getPhenotypeById(parentId.or("wat"));
        pt.setParent(parent);
        try {
            Phenotype existing = checkInDb(pt);
            if (!Phenotype.NULL.equals(existing)) {
                return updatePhenotype(existing);
            }
            existing = checkInGithub(pt);
            if (!Phenotype.NULL.equals(existing)) {
                return updatePhenotype(existing);
            }
            /* It wasn't anywhere */
            github.openIssue(pt);
            db.savePhenotype(pt);
        } catch (IOException e) {
            throw new TermRequesterBackendException("Github API threw exception", e);
        }
        return pt;
    }

    /**
     * Check if the given phenotype exists in the db; if so merge it and return it.
     * If the phenotype is present in the db but not in github, will create it in github.
     * @param pt the phenotype to check for
     * @return the existing phenotype or Phenotype.NULL if none existed.
     */
    private Phenotype checkInDb(Phenotype pt) throws IOException
    {

        Phenotype existing = db.getPhenotype(pt);
        if (!Phenotype.NULL.equals(existing)) {
            existing.mergeWith(pt);
            if (existing.submittable() && !(github.searchForIssue(existing).isPresent())) {
                /* We're out of sync, so submit this issue to github */
                github.openIssue(existing);
            }
            return existing;
        }
        return Phenotype.NULL;
    }

    /**
     * Check if the given phenotype exists in github; if so, merge it and return it.
     * If the phenotype is in github but not in the database, data will most likely have been lost,
     * so throws.
     * @param pt the phenotype to check for
     * @return the existing phenotype or Phenotype.NULL if none existed.
     * @throws RuntimeException if the phenotype is in github but not in the database.
     */
    private Phenotype checkInGithub(Phenotype pt) throws IOException
    {

        Optional<String> number = github.searchForIssue(pt);
        if (number.isPresent()) {
            Phenotype existing = db.getPhenotypeByIssueNumber(number.get());
            if (Phenotype.NULL.equals(existing)) {
                throw new RuntimeException("Phenotype with issue number " + number.get() + " is in github but not database.");
            }
            existing.mergeWith(pt);
            return existing;
        }
        return Phenotype.NULL;
    }

    /**
     * Persist the phenotype given and return it - does not necessarily create it.
     * @param pt the phenotype
     * @return the phenotype
     */
    private Phenotype updatePhenotype(Phenotype pt) throws IOException
    {
        db.savePhenotype(pt);
        github.patchIssue(pt);
        return pt;
    }

    @Override
    public Phenotype getPhenotypeById(String id) throws TermRequesterBackendException
    {
        Phenotype pt = db.getPhenotypeById(id);
        try {
            if (pt.getIssueNumber().isPresent()) {
                Phenotype.Status status = github.getStatus(pt);
                if (status != pt.getStatus()) {
                    pt.setStatus(status);
                    db.savePhenotype(pt);
                }
            }
        } catch (IOException e) {
            throw new TermRequesterBackendException("Github API threw exception", e);
        }
        return pt;
    }

    @Override
    public List<Phenotype> search(String text) throws TermRequesterBackendException
    {
        throw new UnsupportedOperationException();
    }
}
