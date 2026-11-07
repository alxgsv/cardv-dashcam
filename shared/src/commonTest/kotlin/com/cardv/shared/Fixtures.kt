package com.cardv.shared

/**
 * Sanitized fixtures copied from docs/PROTOCOL.md and the probe reports.
 *
 * Names and times are the ones documented there; no real camera identifiers are included.
 */
object Fixtures {
    const val KV_CAMERA_EV: String = """0
OK
Camera.Menu.EV=EV0
"""

    const val KV_CAMERA_DB: String = """0
OK
Camera.DBFileNum=54+125
Camera.TimeDate=2022/7/20 21/12/49
Camera.Menu.SD0=READY
"""

    const val KV_UNKNOWN_PROPERTY: String = """703
Unknown CGI-PARAM: Property
"""

    const val SET_OK: String = """0
OK
"""

    const val SET_REFUSED: String = """709
Refused while recording
"""

    const val DIR_DCIM: String = """<DCIM>
<file>
  <name>/SD/MOVIE/20220707_162655_002088.MP4</name>
  <format size="1920x1080" fps="30" time="300.2">MP4</format>
  <size>489291776</size>
  <attr>RW</attr>
  <time>2022-07-07 16:26:55</time>
</file>
<file>
  <name>/SD/MOVIE/20220707_163155_002089.MP4</name>
  <format size="1920x1080" fps="30" time="300.2">MP4</format>
  <size>489291776</size>
  <attr>RW</attr>
  <time>2022-07-07 16:31:55</time>
</file>
<amount>2</amount>
</DCIM>
"""

    const val DIR_SOS: String = """<SOS>
<file>
  <name>/SD/SOS/20220707_163155_000001.MP4</name>
  <format size="1920x1080" fps="30" time="30.0">MP4</format>
  <size>49152000</size>
  <attr>RW</attr>
  <time>2022-07-07 16:31:55</time>
</file>
<amount>1</amount>
</SOS>
"""

    const val DIR_PHOTO: String = """<Photo>
<file>
  <name>/SD/PHOTO/20220707_190000_000000.JPG</name>
  <format size="480x272" fps="0" time="0">JPG</format>
  <size>120000</size>
  <attr>RW</attr>
  <time>2022-07-07 19:00:00</time>
</file>
<amount>1</amount>
</Photo>
"""

    /** Wrongly-cased `Lock` is a folder name the camera silently turns into the loop list. */
    const val DIR_LOCK_FALLBACK: String = """<Lock>
<file>
  <name>/SD/MOVIE/20220707_162655_002088.MP4</name>
  <format size="1920x1080" fps="30" time="300.2">MP4</format>
  <size>489291776</size>
  <attr>RW</attr>
  <time>2022-07-07 16:26:55</time>
</file>
<amount>1</amount>
</Lock>
"""

    const val DIR_EMPTY: String = """<SOS>
<amount>0</amount>
</SOS>
"""
}
