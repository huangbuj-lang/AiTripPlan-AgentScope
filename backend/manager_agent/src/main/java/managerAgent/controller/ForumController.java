package managerAgent.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import managerAgent.service.ForumService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 论坛接口（P1）：三版块 / 发帖 / 评论(两级楼中楼) / 点赞收藏 / 搜索 / 同款行程复制 / 业务日配额。 */
@RestController
@RequestMapping("/api/forum")
public class ForumController {

    @Resource
    private ForumService forumService;

    private long uid(HttpSession session) {
        return ((Number) session.getAttribute("userId")).longValue();
    }

    //==================== 版块 / 帖子 ====================

    @GetMapping("/boards")
    public Map<String, Object> boards() {
        return forumService.boards();
    }

    /** 帖子列表：按版块过滤、排序(latest/hot)、关键词搜索、分页 */
    @GetMapping("/posts")
    public Map<String, Object> posts(@RequestParam(required = false) Integer board,
                                     @RequestParam(required = false, defaultValue = "latest") String sort,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false, defaultValue = "0") int page,
                                     @RequestParam(required = false, defaultValue = "10") int pageSize) {
        return forumService.posts(board, sort, q, page, pageSize);
    }

    /** 发帖（可关联行程 + 结伴字段） */
    @PostMapping("/posts")
    public Map<String, Object> createPost(@RequestParam String title,
                                          @RequestParam String content,
                                          @RequestParam int boardId,
                                          @RequestParam(required = false) Long tripId,
                                          @RequestParam(required = false) String destination,
                                          @RequestParam(required = false) String travelDate,
                                          HttpSession session) {
        return forumService.createPost(uid(session), title, content, boardId, tripId, destination, travelDate);
    }

    /** 帖子详情（浏览量 +1） */
    @GetMapping("/posts/{id}")
    public Map<String, Object> postDetail(@PathVariable Long id) {
        return forumService.postDetail(id);
    }

    /** 删除帖子（仅本人） */
    @DeleteMapping("/posts/{id}")
    public Map<String, Object> deletePost(@PathVariable Long id, HttpSession session) {
        return forumService.deletePost(id, uid(session));
    }

    //==================== 评论 ====================

    @GetMapping("/posts/{id}/comments")
    public Map<String, Object> comments(@PathVariable Long id) {
        return forumService.comments(id);
    }

    /** 发表评论（两级楼中楼：parent_id 指向被回复评论） */
    @PostMapping("/posts/{id}/comments")
    public Map<String, Object> addComment(@PathVariable Long id,
                                          @RequestParam String content,
                                          @RequestParam(required = false) Long parentId,
                                          @RequestParam(required = false) Long replyUserId,
                                          HttpSession session) {
        return forumService.addComment(id, uid(session), content, parentId, replyUserId);
    }

    //==================== 点赞 / 收藏 ====================

    /** 点赞（切换：点赞<->取消） */
    @PostMapping("/posts/{id}/like")
    public Map<String, Object> like(@PathVariable Long id, HttpSession session) {
        return forumService.like(id, uid(session));
    }

    /** 收藏（切换） */
    @PostMapping("/posts/{id}/favorite")
    public Map<String, Object> favorite(@PathVariable Long id, HttpSession session) {
        return forumService.favorite(id, uid(session));
    }

    //==================== AI 答主 / 采纳 / 举报 ====================

    /** 请求 AI 答主回答（仅楼主，问答版块） */
    @PostMapping("/answers/ai")
    public Map<String, Object> aiAnswer(@RequestParam Long postId, HttpSession session) {
        return forumService.aiAnswer(postId, uid(session));
    }

    /** 采纳 AI 回答（仅楼主，采纳后标注「已采纳」） */
    @PostMapping("/comments/{id}/adopt")
    public Map<String, Object> adopt(@PathVariable Long id, HttpSession session) {
        return forumService.adopt(id, uid(session));
    }

    /** 举报帖子/评论（进管理员审核队列） */
    @PostMapping("/reports")
    public Map<String, Object> report(@RequestParam int targetType,
                                      @RequestParam Long targetId,
                                      @RequestParam String reason,
                                      HttpSession session) {
        return forumService.report(uid(session), targetType, targetId, reason);
    }

    //==================== 同款行程复制 ====================

    /** 复制帖子内嵌行程到自己账户 */
    @PostMapping("/posts/{id}/copy-trip")
    public Map<String, Object> copyTrip(@PathVariable Long id, HttpSession session) {
        return forumService.copyTrip(id, uid(session));
    }

    //==================== 我的内容 ====================

    @GetMapping("/mine")
    public Map<String, Object> mine(@RequestParam(required = false, defaultValue = "post") String type,
                                    HttpSession session) {
        return forumService.mine(uid(session), type);
    }
}
