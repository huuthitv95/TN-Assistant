package vn.tn.assistant.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Scroller
import vn.tn.assistant.R
import kotlin.math.*

/** Source c2.d/w/h and ChatHeadService: reversible drag, magnet and user-hide. */
internal class TnFloatingDragController(
    private val context:Context,private val windows:WindowManager,
    private val view:TnFloatingClusterView,private val layout:WindowManager.LayoutParams,
    private val moved:(Int,Int)->Unit,private val savePosition:()->Unit,private val hide:()->Unit
) {
    private val x=TnFloatingSpring(layout.x.toDouble(),200.0,20.0)
    private val y=TnFloatingSpring(layout.y.toDouble(),200.0,20.0)
    private val scale=TnFloatingSpring(1.0,200.0,20.0)
    // c2.d's ImageView initially displays .75, but its new scale spring is 0.
    private val closeX=TnFloatingSpring();private val closeY=TnFloatingSpring();private val closeScale=TnFloatingSpring()
    private val targetSize=context.resources.getDimensionPixelSize(R.dimen.tn_floating_close_target_size)
    private val metrics get()=context.resources.displayMetrics
    private val targetView=ImageView(context).apply{setImageResource(R.drawable.tn_floating_close_target);scaleType=ImageView.ScaleType.FIT_XY;scaleX=.75f;scaleY=.75f;visibility=View.GONE;contentDescription="Xóa tiện ích nổi"}
    private val gradient=View(context).apply{background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0,0x61000000));visibility=View.GONE}
    private fun overlay(width:Int,height:Int,flags:Int)=WindowManager.LayoutParams(width,height,if(Build.VERSION.SDK_INT>=26)WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.LEFT}
    // Original gravity 81 measures y upward from the bottom. TOP gravity adds
    // the status-bar inset and clamps this target at the bottom on API 28.
    private val targetLayout=overlay(targetSize,targetSize,WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply{gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL}
    private val gradientLayout=overlay(metrics.widthPixels,min((targetSize*2.19f).toInt(),metrics.heightPixels),WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    private var attached=false;private var showing=false;private var magnet=false;private var dragging=false
    private var downX=0f;private var downY=0f;private var anchorX=0;private var anchorY=0
    private var downAt=0L;private var tracker:VelocityTracker?=null
    private val targetLocation=IntArray(2)
    private var persistWhenSettled=false
    private val mainSystem=TnFloatingSpringSystem(onIdle={
        if(persistWhenSettled){persistWhenSettled=false;savePosition()}
    })
    private val closeSystem=TnFloatingSpringSystem()
    private val fling=Scroller(context).apply{setFriction(ViewConfiguration.getScrollFriction()*10f)}
    init {
        mainSystem.register(x){moved(it.toInt(),y.value.toInt())}
        mainSystem.register(y){moved(x.value.toInt(),it.toInt())}
        mainSystem.register(scale){view.scaleX=it.toFloat();view.scaleY=it.toFloat()}
        closeSystem.register(closeX){targetLayout.x=it.toInt();if(attached)safelyUpdateTarget()}
        closeSystem.register(closeY){targetLayout.y=it.toInt();if(attached)safelyUpdateTarget()}
        closeSystem.register(closeScale){targetView.scaleX=it.toFloat();targetView.scaleY=it.toFloat()}
    }
    private fun targetCenter():Pair<Int,Int> {
        // ChatHeadService.B reads the laid-out view, including its scale and
        // the window manager's bounds, rather than the next spring position.
        if(attached&&showing&&targetView.isLaidOut){
            targetView.getLocationOnScreen(targetLocation)
            return(targetLocation[0]+targetView.width/2)to(targetLocation[1]+targetView.height/2)
        }
        val left=(metrics.widthPixels-targetSize)/2+closeX.value.toInt()
        val top=(metrics.heightPixels-targetSize-closeY.value.toInt()).coerceIn(0,(metrics.heightPixels-targetSize).coerceAtLeast(0))
        return(left+targetSize/2)to(top+targetSize/2)
    }
    private fun safelyUpdateTarget(){ try { windows.updateViewLayout(targetView,targetLayout) } catch (_:IllegalArgumentException) {} catch (_:SecurityException) { dispose() } }
    private fun updateTarget(){
        targetLayout.x=closeX.value.toInt();targetLayout.y=closeY.value.toInt()
        targetView.scaleX=closeScale.value.toFloat();targetView.scaleY=closeScale.value.toFloat()
        safelyUpdateTarget()
    }
    private fun showTarget(){
        if(showing)return
        if(!attached){
            gradientLayout.y=metrics.heightPixels-gradientLayout.height
            // ChatHeadService adds its close window before the drag shade.
            // The shade also dims the close icon, rather than leaving it white.
            try { windows.addView(targetView,targetLayout); windows.addView(gradient,gradientLayout); attached=true } catch (_:RuntimeException) { try { windows.removeView(targetView) } catch (_:RuntimeException) {}; return }
        }
        showing=true;closeX.configure(200.0,20.0);closeY.configure(200.0,20.0)
        gradient.visibility=View.VISIBLE;gradient.alpha=0f;gradient.animate().alpha(1f)
        targetView.animate().cancel();targetView.translationY=0f;targetView.visibility=View.VISIBLE
        closeSystem.snap(closeScale,.6f.toDouble());closeSystem.target(closeScale,.75);updateTarget()
    }
    private fun hideTarget(){
        if(!showing)return
        closeSystem.stopAtCurrent(closeX);closeSystem.stopAtCurrent(closeY)
        showing=false;targetView.animate().translationY(2f*targetSize).withEndAction{targetView.visibility=View.GONE}
        gradient.animate().alpha(0f).withEndAction{gradient.visibility=View.GONE}
    }
    fun down(event:MotionEvent){
        persistWhenSettled=false;downX=event.rawX;downY=event.rawY;anchorX=layout.x;anchorY=layout.y
        downAt=SystemClock.uptimeMillis();dragging=false;magnet=false
        mainSystem.snap(x,layout.x.toDouble());mainSystem.snap(y,layout.y.toDouble());x.configure(200.0,20.0);y.configure(200.0,20.0)
        tracker?.recycle();tracker=VelocityTracker.obtain();addVelocity(event)
    }
    private fun addVelocity(event:MotionEvent){
        // Record absolute positions, independent of the moving overlay window.
        val absolute=MotionEvent.obtain(event);absolute.setLocation(event.rawX,event.rawY);tracker?.addMovement(absolute);absolute.recycle()
    }
    fun move(event:MotionEvent){
        val dx=event.rawX-downX;val dy=event.rawY-downY;addVelocity(event)
        if(!dragging&&hypot(dx,dy)>ViewConfiguration.get(context).scaledTouchSlop*3f)dragging=true
        if(!dragging)return
        if(SystemClock.uptimeMillis()-downAt>200)showTarget()
        // Match the original double range mapping before integer window
        // coordinates: float division made 5 and 17 truncate to 4 and 16.
        val rangeX=metrics.widthPixels*.05f;val rangeY=metrics.heightPixels*.05f
        if(showing){
            closeSystem.target(closeX,event.rawX.toDouble()/metrics.widthPixels*rangeX-rangeX/2.0)
            closeSystem.target(closeY,event.rawY.toDouble()/metrics.heightPixels*rangeY-rangeY/2.0)
        }
        val nextX=anchorX+dx;val nextY=anchorY+dy;val(centerX,centerY)=targetCenter()
        // b2.o uses distance from the close center to the entire body rectangle.
        // Its separate hint does not enlarge that rectangle. Center-distance
        // checks miss edge captures, especially on smaller floating controls.
        magnet=showing&&TnFloatingGeometry.nearCloseTarget(centerX,centerY,
            nextX+view.dragBodyLeft,nextY+view.dragBodyTop,
            view.dragBodyWidth,view.dragBodyHeight,targetSize,magnet)
        if(magnet){
            mainSystem.target(scale,min(targetSize.toDouble()/view.dragBodyWidth,1.0))
            x.configure(50.0,5.0);y.configure(50.0,5.0)
            mainSystem.target(x,(centerX-view.dragBodyLeft-view.dragBodyWidth/2).toDouble())
            mainSystem.target(y,(centerY-view.dragBodyTop-view.dragBodyHeight/2).toDouble())
            closeSystem.target(closeScale,1.0)
        }else{
            mainSystem.target(scale,.875)
            x.configure(200.0,20.0);y.configure(200.0,20.0);mainSystem.target(x,nextX.toDouble());mainSystem.target(y,nextY.toDouble());closeSystem.target(closeScale,.75)
        }
        // b2/o.smali uses 0x3e8. Jadx resolves this through a misleading
        // external constant; the compiled instruction is 1000 units/second.
        tracker?.computeCurrentVelocity(1000)
    }
    fun release(event:MotionEvent,cancelled:Boolean=false):Boolean {
        val wasDragging=dragging;dragging=false
        // Original o.smali sends UP(1) and CANCEL(3) into the same release
        // branch. b2.d returns false for CANCEL, so Scroller/close still run.
        if(wasDragging||cancelled){
            if(magnet){
                hideTarget();tracker?.recycle();tracker=null;hide();return true
            }
            val vx=tracker?.xVelocity?:0f
            val vy=tracker?.yVelocity?:0f
            val rawX=(anchorX+event.rawX-downX).toInt();val rawY=(anchorY+event.rawY-downY).toInt()
            // The compiled release branch runs Scroller after a drag even at
            // zero speed; its separate tap result, not minimum speed, gates it.
            fling.fling(rawX,rawY,vx.toInt(),vy.toInt(),0,(metrics.widthPixels-layout.width).coerceAtLeast(0),0,(metrics.heightPixels-layout.height).coerceAtLeast(0))
            mainSystem.target(x,fling.finalX.toDouble())
            // The Original sign-layout callback immediately requests a
            // second origin position after x0.f publishes the released
            // position. Reserve its empty sign slots as b2.s.d does.
            val maximum=TnFloatingGeometry.maximumOriginY(metrics.heightPixels,view.unit.toFloat(),context.resources.getDimension(R.dimen.tn_floating_sign_spacing))
            mainSystem.target(y,min(fling.finalY,maximum).toDouble())
            x.configure(200.0,20.0);y.configure(200.0,20.0);mainSystem.target(scale,1.0);magnet=false;hideTarget();persistWhenSettled=true
        }else{mainSystem.target(scale,1.0)}
        tracker?.recycle();tracker=null;return wasDragging
    }
    fun resized(){mainSystem.snap(x,layout.x.toDouble());mainSystem.snap(y,layout.y.toDouble())}
    fun dispose(){
        mainSystem.dispose();closeSystem.dispose();tracker?.recycle();tracker=null
        targetView.animate().cancel();gradient.animate().cancel()
        if(attached){try{windows.removeView(targetView)}catch(_:RuntimeException){};try{windows.removeView(gradient)}catch(_:RuntimeException){};attached=false}
    }
}
