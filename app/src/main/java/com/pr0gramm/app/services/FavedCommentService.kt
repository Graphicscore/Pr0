package com.pr0gramm.app.services

import com.google.gson.GsonBuilder
import com.pr0gramm.app.api.InstantTypeAdapter
import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.feed.ContentType
import com.pr0gramm.app.util.BackgroundScheduler
import com.pr0gramm.app.util.onErrorResumeEmpty
import gnu.trove.TCollections
import gnu.trove.set.TLongSet
import gnu.trove.set.hash.TLongHashSet
import okhttp3.OkHttpClient
import org.joda.time.Instant
import org.slf4j.LoggerFactory
import retrofit2.Retrofit
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 */
@Singleton
class FavedCommentService @Inject constructor(userService: UserService, okHttpClient: OkHttpClient) {
    private val api = Retrofit.Builder()
            .client(okHttpClient)
            .baseUrl("https://pr0.wibbly-wobbly.de/api/comments/v1/")
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder()
                    .registerTypeAdapterFactory(GsonAdaptersFavedComment())
                    .registerTypeAdapter(Instant::class.java, InstantTypeAdapter())
                    .create()))
            .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
            .validateEagerly(true)
            .build()
            .create(HttpInterface::class.java)

    private val userHash: Observable<String> = userService.userToken()
            .distinctUntilChanged().replay(1).autoConnect()

    private val favCommentIds = TLongHashSet()
    private val favCommentIdsObservable = BehaviorSubject.create<TLongSet>(TLongHashSet()).toSerialized()

    private val forceUpdateUserHash = PublishSubject.create<String>().toSerialized()

    val favedCommentIds: Observable<TLongSet>
        get() = favCommentIdsObservable.asObservable()

    init {
        // update comments when the login state changes
        userHash.mergeWith(forceUpdateUserHash.observeOn(BackgroundScheduler.instance()))
                .switchMap { userHash ->
                    if (userHash == null)
                        Observable.just(emptyList())
                    else
                        api.list(userHash, ContentType.combine(ContentType.All))
                                .onErrorResumeEmpty()
                }

                .subscribeOn(BackgroundScheduler.instance())
                .subscribe { comments -> updateCommentIds(TLongHashSet(comments.map { it.id })) }
    }

    private fun updateCommentIds(commentIds: TLongHashSet) {
        logger.info("updating comment cache, setting {} comments", commentIds.size())

        synchronized(favCommentIds) {
            favCommentIds.clear()
            favCommentIds.addAll(commentIds)
            updateAfterChange()
        }
    }

    fun save(comment: FavedComment): Observable<Void> {
        logger.info("save comment-fav with id {}", comment.id)

        Track.commentFaved()

        synchronized(favCommentIds) {
            if (favCommentIds.add(comment.id))
                updateAfterChange()
        }

        return userHash.takeFirst { isUserHashAvailable(it) }
                .flatMap { hash -> api.save(hash, comment.id, comment) }
                .ignoreElements()
    }

    fun list(contentType: EnumSet<ContentType>): Observable<List<FavedComment>> {
        val flags = ContentType.combine(contentType)

        Track.listFavedComments()

        // update cache too
        updateCache()

        return userHash.takeFirst { isUserHashAvailable(it) }
                .flatMap { hash -> api.list(hash, flags) }
    }

    fun delete(commentId: Long): Observable<Void> {
        logger.info("delete comment-fav with id {}", commentId)

        synchronized(favCommentIds) {
            if (favCommentIds.remove(commentId)) {
                updateAfterChange()
            }
        }

        return userHash.takeFirst { isUserHashAvailable(it) }
                .flatMap { hash -> api.delete(hash, commentId) }
                .ignoreElements()
    }

    private fun updateAfterChange() {
        // sends the hash set to all the observers
        favCommentIdsObservable.onNext(TCollections.unmodifiableSet(TLongHashSet(favCommentIds)))
    }

    private fun isUserHashAvailable(userHash: String?): Boolean {
        return userHash != null
    }

    fun updateCache() {
        userHash.take(1).subscribe({ forceUpdateUserHash.onNext(it) }, {})
    }

    private interface HttpInterface {
        @DELETE("{userHash}/{commentId}")
        fun delete(
                @Path("userHash") userHash: String,
                @Path("commentId") commentId: Long): Observable<Void>

        @PUT("{userHash}/{commentId}")
        fun save(
                @Path("userHash") userHash: String,
                @Path("commentId") commentId: Long,
                @Body comment: FavedComment): Observable<Void>

        @GET("{userHash}")
        fun list(
                @Path("userHash") userHash: String,
                @Query("flags") flags: Int): Observable<List<FavedComment>>

    }

    companion object {
        private val logger = LoggerFactory.getLogger("CommentService")
        private val regex = "^.*pr0gramm.com/".toRegex()

        fun commentToMessage(comment: FavedComment): Api.Message {
            val thumbnail = comment.thumb.replaceFirst(regex, "/")
            return com.pr0gramm.app.api.pr0gramm.ImmutableApi.Message.builder()
                    .id(comment.id)
                    .itemId(comment.itemId)
                    .name(comment.name)
                    .message(comment.content)
                    .score(comment.up - comment.down)
                    .thumbnail(thumbnail)
                    .creationTime(comment.created)
                    .mark(comment.mark)

                    /* we dont have the sender :/ */
                    .senderId(0)

                    .build()
        }
    }
}
