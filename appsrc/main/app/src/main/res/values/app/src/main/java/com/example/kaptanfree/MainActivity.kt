package com.example.kaptanfree

import android.Manifest
import android.app.*
import android.os.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import android.view.*
import android.widget.*
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

data class P(val x:Double,val y:Double,var z:Double)

class MainActivity:Activity(), SensorEventListener{
    lateinit var lat:EditText; lateinit var lon:EditText; lateinit var area:EditText; lateinit var spacing:EditText
    lateinit var value:EditText; lateinit var info:TextView; lateinit var canvas:HeatView
    val pts=mutableListOf<P>(); var magnetic=0.0
    lateinit var sm:SensorManager; var magSensor:Sensor?=null
    val magSamples=ArrayDeque<Double>()
    var scanning=false
    var grid=Array(0){DoubleArray(0)}; var minZ=0.0; var maxZ=0.0

    override fun onCreate(b:Bundle?){super.onCreate(b)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,16,16,16)}
        root.addView(TextView(this).apply{text="کاوش — اسکن مغناطیسی و تحلیل زمین";textSize=23f})
        lat=field("Latitude"); lon=field("Longitude"); area=field("مساحت m²","100"); spacing=field("فاصله شبکه m","2")
        value=field("Z / مقدار اندازه‌گیری","0")
        listOf(lat,lon,area,spacing,value).forEach{root.addView(it)}
        val r1=LinearLayout(this)
        fun btn(t:String, f:()->Unit)=Button(this).apply{text=t;setOnClickListener{f()}}
        r1.addView(btn("GPS"){gps()}); r1.addView(btn("شبکه"){makeGrid()}); r1.addView(btn("شروع برداشت"){startScan()}); r1.addView(btn("توقف"){stopScan()}); r1.addView(btn("تحلیل"){analyze()})
        root.addView(r1)
        val r2=LinearLayout(this)
        r2.addView(btn("ورود XYZ"){importXYZ()}); r2.addView(btn("XYZ/DAT"){exportXYZ()}); r2.addView(btn("KMZ"){exportKMZ()}); r2.addView(btn("Voxel 3D"){showVoxel()})
        root.addView(r2)
        info=TextView(this).apply{text="داده آماده است."}; root.addView(info)
        canvas=HeatView(this); root.addView(canvas,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        sm=getSystemService(SENSOR_SERVICE) as SensorManager
        magSensor=sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) ?: sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if(magSensor==null) info.text="این گوشی سنسور مغناطیسی ندارد."
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),7)
    }
    fun field(h:String,v:String="")=EditText(this).apply{hint=h;setText(v)}
    fun gps(){
        val lm=getSystemService(LOCATION_SERVICE) as LocationManager
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return
        val l=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if(l!=null){lat.setText(l.latitude.toString());lon.setText(l.longitude.toString());info.text="GPS ${l.latitude}, ${l.longitude}  دقت ${l.accuracy}m"}
        else info.text="GPS آماده نیست؛ مکان‌یابی را روشن کنید."
    }
    fun startScan(){
        if(magSensor==null){info.text="سنسور مغناطیسی روی گوشی پیدا نشد.";return}
        scanning=true; sm.registerListener(this,magSensor,SensorManager.SENSOR_DELAY_GAME)
        info.text="برداشت مغناطیسی فعال است. گوشی را از اجسام فلزی دور نگه دارید و روی هر نقطه ثابت بمانید."
    }
    fun stopScan(){scanning=false;sm.unregisterListener(this)}
    override fun onSensorChanged(e:SensorEvent){
        if(!scanning)return
        val x=e.values[0].toDouble();val y=e.values[1].toDouble();val z=e.values[2].toDouble()
        magnetic=sqrt(x*x+y*y+z*z)
        if(magSamples.size>=20)magSamples.removeFirst()
        magSamples.addLast(magnetic)
        val avg=magSamples.average()
        value.setText("%.2f".format(avg))
    }
    override fun onAccuracyChanged(sensor:Sensor?, accuracy:Int){}
    override fun onPause(){super.onPause(); if(scanning) stopScan()}

    fun makeGrid(){
        val la=lat.text.toString().toDoubleOrNull();val lo=lon.text.toString().toDoubleOrNull()
        val a=area.text.toString().toDoubleOrNull()?:100.0;val s=spacing.text.toString().toDoubleOrNull()?:2.0
        if(la==null||lo==null){info.text="ابتدا مختصات را وارد کنید.";return}
        val side=sqrt(a);val n=max(1,floor(side/s).toInt());pts.clear()
        val ml=111320.0;val mn=111320.0*cos(Math.toRadians(la))
        for(i in -n..n)for(j in -n..n)pts.add(P(lo+i*s/mn,la+j*s/ml,magSamples.average().takeIf{it.isFinite()} ?: magnetic))
        info.text="شبکه ${pts.size} نقطه ساخته شد. برای مقدار واقعی Z، داده برداشت‌شده وارد کنید."; analyze()
    }
    fun analyze(){
        if(pts.size<3){info.text="حداقل ۳ نقطه لازم است.";return}
        val nx=60;val ny=60;grid=Array(ny){DoubleArray(nx)}
        val xs=pts.map{it.x};val ys=pts.map{it.y};val zs=pts.map{it.z};val xmin=xs.min();val xmax=xs.max();val ymin=ys.min();val ymax=ys.max()
        minZ=zs.min();maxZ=zs.max()
        for(j in 0 until ny)for(i in 0 until nx){
            val x=xmin+(xmax-xmin)*i/(nx-1);val y=ymin+(ymax-ymin)*j/(ny-1)
            var sw=0.0;var sz=0.0
            for(p in pts){val d=hypot((p.x-x)*111320*cos(Math.toRadians(y)),(p.y-y)*111320);val w=1.0/(d*d+1e-12);sw+=w;sz+=w*p.z}
            grid[j][i]=sz/sw
        }
        canvas.invalidate();info.text="Grid ${nx}×${ny} ساخته شد | Z: $minZ تا $maxZ"
    }
    fun importXYZ(){
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="text/*";addCategory(Intent.CATEGORY_OPENABLE)}
        startActivityForResult(i,20)
    }
    override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==20&&c==RESULT_OK&&d?.data!=null){
        try{contentResolver.openInputStream(d.data!!)!!.bufferedReader().useLines{lines->
            pts.clear();lines.forEach{ln->val q=ln.trim().split(Regex("[,;\\t ]+"));if(q.size>=3){
                val x=q[0].toDoubleOrNull();val y=q[1].toDoubleOrNull();val z=q[2].toDoubleOrNull()
                if(x!=null&&y!=null&&z!=null)pts.add(P(x,y,z))
            }}}
            info.text="${pts.size} نقطه وارد شد";analyze()
        }catch(e:Exception){info.text="خطا در خواندن XYZ: ${e.message}"}}
    }
    fun baseFile(ext:String)=File(getExternalFilesDir(null),"scan_${System.currentTimeMillis()}.$ext")
    fun exportXYZ(){
        if(pts.isEmpty()){info.text="داده‌ای وجود ندارد.";return}
        val f=baseFile("dat");f.writeText(pts.joinToString("\n"){"${it.x}\t${it.y}\t${it.z}"})
        share(f);info.text="DAT/XYZ ذخیره شد: ${f.name}"
    }
    fun exportKMZ(){
        if(pts.isEmpty()){info.text="داده‌ای وجود ندارد.";return}
        val f=baseFile("kmz");val kml="""<?xml version="1.0" encoding="UTF-8"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><n>Scan</n>${pts.joinToString(""){"<Placemark><n>${it.z}</n><Point><coordinates>${it.x},${it.y},${it.z}</coordinates></Point></Placemark>"}}</Document></kml>"""
        ZipOutputStream(FileOutputStream(f)).use{z->z.putNextEntry(ZipEntry("doc.kml"));z.write(kml.toByteArray());z.closeEntry()}
        share(f);info.text="KMZ ذخیره شد: ${f.name}"
    }

    fun showVoxel(){
        if(grid.isEmpty()){info.text="ابتدا داده را تحلیل کنید.";return}
        val d=Dialog(this)
        val v=VoxelView(this)
        d.setTitle("Voxel 3D")
        d.setContentView(v)
        d.window?.setLayout(-1,-1)
        d.show()
    }
    inner class VoxelView(c:Context):View(c){
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){
            c.drawColor(Color.WHITE)
            val ny=grid.size; val nx=grid[0].size
            val sx=width.toFloat()/nx; val sy=height.toFloat()/ny
            for(layer in 0 until 8){
                val offx=layer*10f; val offy=layer*8f
                val scale=(1f-layer*0.045f).coerceAtLeast(.65f)
                for(j in 0 until ny step 3) for(i in 0 until nx step 3){
                    val t=((grid[j][i]-minZ)/(maxZ-minZ+1e-12)).coerceIn(0.0,1.0)
                    val rr=(255*t).toInt()
                    val bb=(255*(1-t)).toInt()
                    p.color=Color.rgb(rr,(80+110*(1-abs(2*t-1))).toInt(),bb)
                    val x=offx+(i*sx)*scale
                    val y=offy+(j*sy)*scale
                    val w=maxOf(2f,sx*scale*3f)
                    val h=maxOf(2f,sy*scale*3f)
                    c.drawRect(x,y,x+w,y+h,p)
                }
            }
            p.color=Color.BLACK;p.textSize=28f
            c.drawText("Voxel 3D (نمایش حجمی)",16f,34f,p)
            p.textSize=16f
            c.drawText("عمق واقعی فقط با داده عمقی واقعی قابل محاسبه است.",16f,58f,p)
        }
    }

    fun share(f:File){startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/octet-stream";putExtra(Intent.EXTRA_STREAM,Uri.fromFile(f))},"اشتراک‌گذاری فایل"))}
    inner class HeatView(c:Context):View(c){
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){super.onDraw(c);c.drawColor(Color.WHITE);if(grid.isEmpty())return
            val ny=grid.size;val nx=grid[0].size
            val w=width.toFloat()/nx;val h=height.toFloat()/ny
            for(j in 0 until ny)for(i in 0 until nx){
                val t=((grid[j][i]-minZ)/(maxZ-minZ+1e-12)).coerceIn(0.0,1.0)
                val col=Color.rgb((255*t).toInt(),(80+120*(1-abs(2*t-1))).toInt(),(255*(1-t)).toInt())
                p.color=col;c.drawRect(i*w,j*h,(i+1)*w,(j+1)*h,p)
            }
            p.color=Color.BLACK;p.textSize=28f;c.drawText("Heatmap / IDW",12f,30f,p)
        }
    }
}
