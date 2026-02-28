package youzi.lin.server.websocket;

/**
 * 从客户端接收到的单帧数据。
 *
 * @param timestampMs 客户端采集时刻（Unix 毫秒时间戳，大端 int64）
 * @param imageData   编码后的图像字节（JPEG / WebP 等，服务端透传不解码）
 */
public record VideoFrameData(long timestampMs, byte[] imageData) {
}

