package me.qoomon.gitversioning.commons;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collector;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.eclipse.jgit.lib.Constants.HEAD;
import static org.eclipse.jgit.lib.Constants.R_TAGS;
import static org.eclipse.jgit.lib.Repository.shortenRefName;

public final class GitUtil {

    public static String NO_COMMIT = "0000000000000000000000000000000000000000";

    public static Status status(Repository repository) {
        try {
            return Git.wrap(repository).status().call();
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    public static String branch(Repository repository) throws IOException {
        String branch = repository.getBranch();
        if (ObjectId.isId(branch)) {
            return null;
        }
        return branch;
    }

    public static List<String> tagsPointAt(Repository repository, String revstr) throws IOException {
        ObjectId revObjectId = repository.resolve(revstr);
        return getReverseTagRefMap(repository).getOrDefault(revObjectId, emptyList()).stream()
                .sorted(new RefNameComparator(repository))
                .map(ref -> shortenRefName(ref.getName()))
                .collect(toList());
    }

    public static GitDescription describe(Repository repository, String revstr, String tagRegex) throws IOException {
        ObjectId revStart = repository.resolve(revstr);
        if (revStart == null) {
            return new GitDescription(NO_COMMIT, null, 0);
        }

        Pattern tagPattern = Pattern.compile(tagRegex);
        Map<ObjectId, List<Ref>> commitTagsMap = getReverseTagRefMap(repository);

        // Walk back commit ancestors looking for tagged one
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setRetainBody(false);
            walk.setFirstParent(true);
            walk.markStart(walk.parseCommit(revStart));
            Iterator<RevCommit> walkIterator = walk.iterator();
            int depth = -1;
            while (walkIterator.hasNext()) {
                RevCommit rev = walkIterator.next();
                depth++;

                List<Ref> tags = commitTagsMap.getOrDefault(rev, emptyList()).stream()
                        .sorted(new RefNameComparator(repository))
                        .collect(toList());
                for (Ref tag : tags) {
                    String tagName = shortenRefName(tag.getName());
                    if (tagPattern.matcher(tagName).matches()) {
                        return new GitDescription(revStart.getName(), tagName, depth);
                    }
                }
            }
            return new GitDescription(revStart.getName(), null, depth);
        }
    }

    private static Map<ObjectId, List<Ref>> getReverseTagRefMap(Repository repository) throws IOException {
        RefDatabase refDatabase = repository.getRefDatabase();
        return refDatabase.getRefsByPrefix(R_TAGS).stream()
                .collect(groupingByTarget(refDatabase));
    }

    private static Collector<Ref, ?, Map<ObjectId, List<Ref>>> groupingByTarget(RefDatabase refDatabase) {
        return groupingBy(ref -> {
            try {
                ObjectId peeledObjectId = refDatabase.peel(ref).getPeeledObjectId();
                return peeledObjectId != null ? peeledObjectId : ref.getObjectId();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static String revParse(Repository repository, String revstr) throws IOException {
        ObjectId rev = repository.resolve(revstr);
        if (rev == null) {
            return NO_COMMIT;
        }
        return rev.getName();
    }

    public static long revTimestamp(Repository repository, String revstr) throws IOException {
        ObjectId rev = repository.resolve(revstr);
        if (rev == null) {
            return 0;
        }
        // The timestamp is expressed in seconds since epoch...
        return repository.parseCommit(rev).getCommitTime();
    }

    public static GitSituation situation(File directory) throws IOException {
        try (Repository repository = new FileRepositoryBuilder().findGitDir(directory).build()) {
            if (repository.getWorkTree() == null) {
                return null;
            }
            String headCommit = GitUtil.revParse(repository, HEAD);
            long headCommitTimestamp = GitUtil.revTimestamp(repository, HEAD);
            String headBranch = GitUtil.branch(repository);
            List<String> headTags = GitUtil.tagsPointAt(repository, HEAD);
            boolean isClean = GitUtil.status(repository).isClean();
            return new GitSituation(repository.getWorkTree(), headCommit, headCommitTimestamp, headBranch, headTags, isClean);
        }
    }
}
