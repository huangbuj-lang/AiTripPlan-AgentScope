package managerAgent.controller;

import jakarta.annotation.Resource;
import managerAgent.service.PersonalMapService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 专属地图：行程 -> 高德地图唤端链接（业务在 PersonalMapService）。 */
@RestController
@RequestMapping("/api/amap")
public class PersonalMapController {

    @Resource
    private PersonalMapService personalMapService;

    /** 生成专属地图唤端链接 */
    @PostMapping("/personal-map")
    public Map<String, Object> personalMap(@RequestBody String body) {
        return personalMapService.personalMap(body);
    }

    /** 代理：把高德静态地图图片字节流回给前端（key 只在后端） */
    @GetMapping("/static-map")
    public ResponseEntity<byte[]> staticMap(@RequestParam String markers, @RequestParam String path,
                                            @RequestParam(defaultValue = "11") int zoom) {
        byte[] img = personalMapService.staticMapImage(markers, path, zoom);
        if (img == null) {
            return ResponseEntity.status(502).build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", "image/png")
                .header("Cache-Control", "max-age=3600")
                .body(img);
    }
}
