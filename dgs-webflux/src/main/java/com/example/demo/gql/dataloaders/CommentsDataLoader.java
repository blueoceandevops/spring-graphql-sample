package com.example.demo.gql.dataloaders;

import com.example.demo.gql.types.Comment;
import com.example.demo.service.PostService;
import com.netflix.graphql.dgs.DgsDataLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dataloader.MappedBatchLoader;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@DgsDataLoader(name = "comments")
@RequiredArgsConstructor
@Slf4j
public class CommentsDataLoader implements MappedBatchLoader<String, List<Comment>> {
    final PostService postService;

    @Override
    public CompletionStage<Map<String, List<Comment>>> load(Set<String> keys) {
        CompletableFuture<List<Comment>> commentsFuture = this.postService.getCommentsByPostIdIn(keys).collectList().toFuture();

        return commentsFuture.thenApplyAsync(comments -> {
            Map<String, List<Comment>> mappedComments = new HashMap<>();
            keys.forEach(k -> mappedComments.put(k, comments
                    .stream()
                    .filter(c -> c.getPostId().equals(k)).toList())
            );
            return mappedComments;
        });
    }
}
