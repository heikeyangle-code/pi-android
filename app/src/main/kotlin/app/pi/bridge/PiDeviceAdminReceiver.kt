package app.pi.bridge

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * 设备管理员的接收器：系统用它把「激活 / 停用」这类状态变化通知给本应用。
 *
 * 刻意是**空实现**：设备管理员状态由系统保存（`dpm.isAdminActive(component)` 随时能
 * 查），这里只需要在状态变化时留一条日志，不写任何自己的持久状态 —— 多存一份镜像就
 * 多一个会和系统不一致的地方。
 *
 * 与 [DeviceAdmin] 的关系：本类是「身份」，[DeviceAdmin] 是「用这个身份能做的动作」。
 * 波2 在 AndroidManifest 里把它声明成
 * `android.app.admin.DeviceAdminReceiver`（`BIND_DEVICE_ADMIN` 权限 +
 * `android.app.device_admin` meta-data → `@xml/pi_device_admin_policies`）。
 */
class PiDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "设备管理员已启用：${context.packageName}")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "设备管理员已停用：${context.packageName}")
    }

    companion object {
        private const val TAG = "PiDeviceAdmin"

        /** 本接收器是否已被系统记为 active 的设备管理员。 */
        fun available(context: Context): Boolean = DeviceAdmin.isAdminActive(context)

        /** 与 [DeviceAdmin.status] 同一份自述，方便「身份」和「能力」分开接线。 */
        fun status(context: Context): JSONObject = DeviceAdmin.status(context)

        fun status(): JSONObject = DeviceAdmin.status()
    }
}
