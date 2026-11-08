package com.neirofm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.neirofm.data.*
import com.neirofm.playback.RadioController

private val Bg=Color(0xFF080611)
private val Panel=Color(0xFF191426)
private val Purple=Color(0xFF963BFF)
private val Muted=Color(0xFFA7A1B8)
private val White=Color(0xFFF8F5FF)

class MainActivity:ComponentActivity(){
 private val folder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){u->if(u!=null){contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);val a=DocumentFile.fromTreeUri(this,u)?.listFiles()?.filter{it.isFile&&it.name?.substringAfterLast('.', "")?.lowercase() in listOf("mp3","m4a","ogg","wav","flac","aac")}?.map{it.uri.toString()}.orEmpty();RadioController.setLibrary(this,"music",a)}}
 private val txt=registerForActivityResult(ActivityResultContracts.OpenDocument()){u->if(u!=null)runCatching{contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);val n=DocumentFile.fromSingleUri(this,u)?.name?: "script.txt";val t=contentResolver.openInputStream(u)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}.orEmpty();ScriptLibrary.import(this,u,n,t)}}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme(colorScheme=darkColorScheme(primary=Purple,background=Bg,surface=Panel,onSurface=White)){Screen(RadioController.library(this,"music").size,ScriptLibrary.get(this),{folder.launch(null)},{txt.launch(arrayOf("text/plain","text/*"))},{RadioController.start(this)},{RadioController.pause(this)},{RadioController.stop(this)},{RadioController.skipPrevious(this)},{RadioController.skipNext(this)},{RadioController.testVoice(this)},{runCatching{startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))}})}}}
}

@Composable private fun Screen(count:Int,scripts:List<RadioScript>,music:()->Unit,txt:()->Unit,play:()->Unit,pause:()->Unit,stop:()->Unit,previous:()->Unit,next:()->Unit,test:()->Unit,install:()->Unit){
 var playing by remember{mutableStateOf(false)};var tab by remember{mutableIntStateOf(0)};var settings by remember{mutableStateOf(false)}
 val program=ProgramCatalog.current()
 Column(Modifier.fillMaxSize().background(Bg)){
  Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){IconButton({settings=!settings}){Icon(Icons.Default.Menu,null,tint=White)};Text("Neiro",fontSize=27.sp,fontWeight=FontWeight.Bold,color=White);Text("FM",fontSize=27.sp,fontWeight=FontWeight.ExtraBold,color=Purple);Spacer(Modifier.weight(1f));IconButton({settings=!settings}){Icon(Icons.Default.Settings,null,tint=White)}}
  when{settings->Column(Modifier.fillMaxWidth().padding(18.dp)){Text("Библиотека и настройки",fontSize=24.sp,color=White,fontWeight=FontWeight.Bold);Setting("Музыкальная папка","$count треков",music);Setting("Импорт TXT-сценария","Реплики, подкасты, реклама",txt);Setting("Проверить голос","Тестовое объявление",test);Setting("Установить голос","Голосовые пакеты Android",install);Text("Имена: podcast_*.txt, reklama_*.txt, pogoda_*.txt, utro_*.txt, noch_*.txt, greeting_*.txt",color=Muted,fontSize=12.sp)}
   tab==2->Column(Modifier.fillMaxSize().padding(18.dp)){Text("Подкасты",fontSize=27.sp,color=White,fontWeight=FontWeight.Bold);Text("Авторские выпуски ведущих и импортированные сценарии",color=Muted);Button(txt,Modifier.fillMaxWidth().padding(vertical=12.dp)){Text("Импортировать TXT")};LazyColumn{items(scripts.filter{it.type==RadioScript.Type.PODCAST||it.type==RadioScript.Type.MORNING||it.type==RadioScript.Type.NIGHT}){ScriptCard(it)}}}
   tab==1->Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.Podcasts,null,tint=Purple,modifier=Modifier.size(80.dp));Text("Прямой эфир",fontSize=28.sp,color=White,fontWeight=FontWeight.Bold);Text(program.name,color=Purple,fontSize=20.sp);Text(program.description,color=Muted,textAlign=TextAlign.Center);Button({if(playing){pause();playing=false}else{play();playing=true}},Modifier.fillMaxWidth().padding(top=20.dp)){Text(if(playing)"Пауза" else "Начать эфир")};OutlinedButton({stop();playing=false},Modifier.fillMaxWidth()){Text("Остановить")};OutlinedButton(test,Modifier.fillMaxWidth()){Text("Проверить ведущего")}}
   tab==3->Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.FavoriteBorder,null,tint=Purple,modifier=Modifier.size(64.dp));Text("Избранное",fontSize=26.sp,color=White);Text("Здесь будут любимые треки",color=Muted)}
   else->Home(count,program.name,playing,{play();playing=true},{pause();playing=false},previous,next,music)
  }
  Spacer(Modifier.weight(1f))
  NavigationBar(containerColor=Color(0xFF100C1B)){listOf("Главная","Радио","Подкасты","Избранное").forEachIndexed{i,s->NavigationBarItem(selected=tab==i,onClick={tab=i;settings=false},icon={Icon(listOf(Icons.Default.Home,Icons.Default.Podcasts,Icons.Default.Mic,Icons.Default.FavoriteBorder)[i],null)},label={Text(s,fontSize=11.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Purple,selectedTextColor=Purple,indicatorColor=Color(0xFF29143F),unselectedIconColor=Muted,unselectedTextColor=Muted))}}
 }
}

@Composable private fun Home(count:Int,program:String,playing:Boolean,play:()->Unit,pause:()->Unit,previous:()->Unit,next:()->Unit,music:()->Unit){
 Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=22.dp),horizontalAlignment=Alignment.CenterHorizontally){
  Box(Modifier.fillMaxWidth().height(250.dp),contentAlignment=Alignment.Center){NeonWaves(Modifier.fillMaxSize());Column(Modifier.size(218.dp).clip(RoundedCornerShape(28.dp)).background(Brush.verticalGradient(listOf(Color(0xFF21083C),Bg))),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Icon(Icons.Default.Headphones,null,tint=Purple,modifier=Modifier.size(100.dp));Text("NeiroFM",fontSize=31.sp,fontWeight=FontWeight.Bold,color=White)}}
  Text(if(playing)"●  В эфире" else "●  Готово к эфиру",color=Color(0xFFD58BFF),modifier=Modifier.clip(CircleShape).background(Color(0xFF29113F)).padding(horizontal=18.dp,vertical=7.dp))
  Text("NeiroFM",fontSize=28.sp,fontWeight=FontWeight.Bold,color=White);Text("Музыка. Настроение. Ты.",fontSize=17.sp,color=Muted);Text(program,color=Purple,fontSize=13.sp)
  Row(Modifier.fillMaxWidth().padding(vertical=18.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){RoundButton(Icons.Default.SkipPrevious,previous,64,Panel);RoundButton(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,{if(playing)pause()else play()},94,Purple);RoundButton(Icons.Default.SkipNext,next,64,Panel)}
  var volume by remember{mutableFloatStateOf(.7f)}
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.VolumeUp,null,tint=Muted);Slider(volume,{volume=it},Modifier.weight(1f).padding(horizontal=10.dp),colors=SliderDefaults.colors(thumbColor=White,activeTrackColor=Purple));Text("${(volume*100).toInt()}%",color=Muted)}
  Row(Modifier.fillMaxWidth().padding(top=10.dp).clip(RoundedCornerShape(20.dp)).background(Panel).padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(68.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Purple,Color(0xFF321A57)))),contentAlignment=Alignment.Center){Icon(Icons.Default.MusicNote,null,tint=White,modifier=Modifier.size(34.dp))};Column(Modifier.weight(1f).padding(start=12.dp)){Text(if(playing)"NeiroFM — эфир" else "Ожидание эфира",color=White,fontWeight=FontWeight.Bold,fontSize=17.sp);Text("$count треков в библиотеке",color=Muted)};Icon(Icons.Default.GraphicEq,null,tint=Purple)}
  Row(Modifier.fillMaxWidth().padding(top=18.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Star,null,tint=Purple);Text("  Избранное",color=White,fontWeight=FontWeight.Bold,fontSize=18.sp);Spacer(Modifier.weight(1f));Icon(Icons.Default.ChevronRight,null,tint=Muted)}
  Row(Modifier.fillMaxWidth().padding(vertical=8.dp).clip(RoundedCornerShape(18.dp)).background(Panel).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){Icon(Icons.Default.FavoriteBorder,null,tint=Muted);Text("  Добавьте любимые треки в избранное",color=Muted,fontSize=13.sp)}
 }
}

@Composable private fun NeonWaves(modifier:Modifier){Canvas(modifier){for(i in 0..6){val p=Path();val y=size.height*.48f+i*13f;p.moveTo(0f,y+50);p.cubicTo(size.width*.2f,y-100,size.width*.3f,y+100,size.width*.5f,y);p.cubicTo(size.width*.7f,y-100,size.width*.8f,y+100,size.width,y-10);drawPath(p,Purple.copy(alpha=.12f+i*.025f),style=androidx.compose.ui.graphics.drawscope.Stroke(1.4f))}}}
@Composable private fun RoundButton(icon:androidx.compose.ui.graphics.vector.ImageVector,click:()->Unit,size:Int,color:Color){Box(Modifier.size(size.dp).clip(CircleShape).background(color).clickable{click()},contentAlignment=Alignment.Center){Icon(icon,null,tint=White,modifier=Modifier.size((size*.43f).dp))}}
@Composable private fun Setting(title:String,detail:String,click:()->Unit){Row(Modifier.fillMaxWidth().padding(vertical=5.dp).clip(RoundedCornerShape(15.dp)).background(Panel).clickable{click()}.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,color=White,fontWeight=FontWeight.SemiBold);Text(detail,color=Muted,fontSize=12.sp)};Icon(Icons.Default.ChevronRight,null,tint=Purple)}}
@Composable private fun ScriptCard(s:RadioScript){Column(Modifier.fillMaxWidth().padding(vertical=5.dp).clip(RoundedCornerShape(15.dp)).background(Panel).padding(14.dp)){Text(s.title,color=White,fontWeight=FontWeight.Bold);Text("${s.type.name} • ${s.presenter}",color=Purple,fontSize=11.sp);Text(s.text.take(220),color=Muted,fontSize=12.sp)}}
