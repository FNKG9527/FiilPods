package moe.chenxy.fiilpods.ui.pages

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.chenxy.fiilpods.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

private fun Context.openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        if (this@openUrl !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    startActivity(intent)
}

@Composable
fun AboutPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SmallTitle(text = stringResource(R.string.about_borrowed)) }
        item {
            Card {
                BasicComponent(
                    title = "OppoPods-Enhanced",
                    summary = "https://github.com/1812z/OppoPods",
                    onClick = {
                        context.openUrl("https://github.com/1812z/OppoPods")
                    }
                )
                BasicComponent(
                    title = "OppoPods",
                    summary = "https://github.com/Leaf-lsgtky/OppoPods",
                    onClick = {
                        context.openUrl("https://github.com/Leaf-lsgtky/OppoPods")
                    }
                )
                BasicComponent(
                    title = "HyperPods",
                    summary = "https://github.com/Art-Chen/HyperPods",
                    onClick = {
                        context.openUrl("https://github.com/Art-Chen/HyperPods")
                    }
                )
                BasicComponent(
                    title = "HuaweiPods",
                    summary = "https://github.com/Nshpiter/HuaweiPods",
                    onClick = {
                        context.openUrl("https://github.com/Nshpiter/HuaweiPods")
                    }
                )
                BasicComponent(
                    title = "OPPOLeaConnect",
                    summary = "https://github.com/Leaf-lsgtky/OPPOLeaConnect",
                    onClick = {
                        context.openUrl("https://github.com/Leaf-lsgtky/OPPOLeaConnect")
                    }
                )
            }
        }

        // 同一作者的 FIIL 系列仓库：协议笔记 → 工具 → 本模块，知识同源。
        item { SmallTitle(text = stringResource(R.string.about_related)) }
        item {
            Card {
                BasicComponent(
                    title = "本项目 · FiilPods",
                    summary = "https://github.com/FNKG9527/FiilPods",
                    onClick = {
                        context.openUrl("https://github.com/FNKG9527/FiilPods")
                    }
                )
                BasicComponent(
                    title = "FIIL 协议逆向笔记",
                    summary = "https://github.com/FNKG9527/fiil-headphone-protocol",
                    onClick = {
                        context.openUrl("https://github.com/FNKG9527/fiil-headphone-protocol")
                    }
                )
                BasicComponent(
                    title = "FIIL 耳机 PC 控制台",
                    summary = "https://github.com/FNKG9527/fiil-pods-pc",
                    onClick = {
                        context.openUrl("https://github.com/FNKG9527/fiil-pods-pc")
                    }
                )
                BasicComponent(
                    title = "FIIL LDAC 离线激活",
                    summary = "https://github.com/FNKG9527/fiil-ldac-unlock",
                    onClick = {
                        context.openUrl("https://github.com/FNKG9527/fiil-ldac-unlock")
                    }
                )
            }
        }
    }
}
