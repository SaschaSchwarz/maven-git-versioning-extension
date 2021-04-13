package me.qoomon.gitversioning.commons;


import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collector;

import static java.time.ZoneOffset.UTC;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.R_TAGS;
import static org.eclipse.jgit.lib.Repository.shortenRefName;

public class GitHeadSituation {

    public static String NO_COMMIT = "0000000000000000000000000000000000000000";

    private final Repository repository;
    private final File rootDirectory;

    private final Lazy<ObjectId> head;

    private final Lazy<String> hash;
    private final Lazy<ZonedDateTime> timestamp;
    private final Lazy<String> branch;

    private final Lazy<Map<ObjectId, List<Ref>>> reverseTagRefMap;
    private final Lazy<List<String>> tags;

    private final Lazy<Boolean> clean;

    private final Pattern descriptionTagPattern;
    private final Lazy<GitDescription> description;

    public GitHeadSituation(Repository repository, Pattern descriptionTagPattern) {
        this.repository = repository;
        this.rootDirectory = repository.getWorkTree();
        this.head = Lazy.of(() -> repository.resolve(HEAD));
        this.hash = Lazy.of(this::hash);
        this.timestamp = Lazy.of(this::timestamp);
        this.branch = Lazy.of(this::branch);

        this.reverseTagRefMap = Lazy.of(this::reverseTagRefMap);
        this.tags = Lazy.of(this::tagsPointAtHead);

        this.clean = Lazy.of(this::clean);

        this.descriptionTagPattern = descriptionTagPattern;
        this.description = Lazy.of(this::description);
    }

    public File getRootDirectory() {
        return rootDirectory;
    }

    public String getHash() {
        return hash.get();
    }

    public ZonedDateTime getTimestamp() {
        return timestamp.get();
    }

    public String getBranch() {
        return branch.get();
    }

    public boolean isDetached() {
        return branch.get() == null;
    }

    public List<String> getTags() {
        return tags.get();
    }

    public boolean isClean() {
        return clean.get();
    }

    public GitDescription getDescription() {
        return description.get();
    }

    // ----- initialization methods ------------------------------------------------------------------------------------

    private String hash() {
        return head.get() != null ? head.get().getName() : NO_COMMIT;
    }

    private ZonedDateTime timestamp() throws IOException {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(
                head.get() != null ? repository.parseCommit(head.get()).getCommitTime() : 0),
                UTC);
    }

    private String branch() throws IOException {
        String branch = repository.getBranch();
        return ObjectId.isId(branch) ? null : branch;
    }

    public List<String> tagsPointAtHead() {
        return reverseTagRefMap.get().getOrDefault(head.get(), emptyList()).stream()
                .sorted(new RefNameComparator(repository))
                .map(ref -> shortenRefName(ref.getName()))
                .collect(toList());
    }

    public Map<ObjectId, List<Ref>> reverseTagRefMap() throws IOException {
        RefDatabase refDatabase = repository.getRefDatabase();
        return refDatabase.getRefsByPrefix(R_TAGS).stream().collect(groupingBy(ref -> {
            try {
                ObjectId peeledObjectId = refDatabase.peel(ref).getPeeledObjectId();
                return peeledObjectId != null ? peeledObjectId : ref.getObjectId();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
    }

    private boolean clean() throws GitAPIException {
        return Git.wrap(repository).status().call().isClean();
    }

    public GitDescription description() throws IOException {
        if (head.get() == null) {
            return new GitDescription(NO_COMMIT, null, 0);
        }

        // Walk back commit ancestors looking for tagged one
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setRetainBody(false);
            walk.setFirstParent(true);
            walk.markStart(walk.parseCommit(head.get()));
            Iterator<RevCommit> walkIterator = walk.iterator();
            int depth = -1;
            while (walkIterator.hasNext()) {
                RevCommit rev = walkIterator.next();
                depth++;

                Optional<Ref> matchingTag = reverseTagRefMap.get().getOrDefault(rev, emptyList()).stream()
                        .sorted(new RefNameComparator(repository))
                        .filter(tag -> descriptionTagPattern.matcher(shortenRefName(tag.getName())).matches())
                        .findFirst();

                if (matchingTag.isPresent()) {
                    return new GitDescription(head.get().getName(), matchingTag.get().getName(), depth);
                }

            }
            return new GitDescription(head.get().getName(), null, depth);
        }
    }


}