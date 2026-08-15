<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { request } from '../api'

type Screen = {
  id: string
  group: string
  title: string
  mock: string
}

const SCREENS: Screen[] = [
  { id: 'c1', group: '顾客 C', title: 'C1 预约首页', mock: '/design-refs/05-C1-预约首页.png' },
  { id: 'c2', group: '顾客 C', title: 'C2 症状路由', mock: '/design-refs/06-C2-症状路由.png' },
  { id: 'c3', group: '顾客 C', title: 'C3 选时段', mock: '/design-refs/07-C3-技师详情选时段.png' },
  { id: 'c4', group: '顾客 C', title: 'C4 确认支付', mock: '/design-refs/08-C4-确认下单支付.png' },
  { id: 'c6', group: '顾客 C', title: 'C6 我的', mock: '/design-refs/10-C6-我的.png' },
  { id: 't1', group: '技师 T', title: 'T1 今日工作台', mock: '/design-refs/14-T1-今日工作台.png' },
  { id: 't2', group: '技师 T', title: 'T2 服务中', mock: '/design-refs/15-T2-服务中理疗记录.png' },
  { id: 'm1', group: '店长 M', title: 'M1 经营概览', mock: '/design-refs/12-M1-经营概览.png' },
]

const screen = ref('c1')
const stores = ref<{ name: string }[]>([])
const therapists = ref<{ name: string; levelLabel: string }[]>([])
const project = ref('头颈肩痛调理 60 分钟')

const current = computed(() => SCREENS.find((s) => s.id === screen.value) || SCREENS[0])

onMounted(async () => {
  try {
    const [s, t, p] = await Promise.all([
      request<{ items: { name: string }[] }>('/api/v1/a/stores').catch(() => ({ items: [] })),
      request<{ items: { name: string; level: string }[] }>('/api/v1/a/therapists').catch(() => ({ items: [] })),
      request<{ items: { name: string }[] }>('/api/v1/a/projects').catch(() => ({ items: [] })),
    ])
    stores.value = (s.items || []).slice(0, 2)
    const level: Record<string, string> = { SENIOR: '资深', MIDDLE: '中级', JUNIOR: '初级' }
    therapists.value = (t.items || []).slice(0, 2).map((x) => ({
      name: x.name,
      levelLabel: level[x.level] || x.level,
    }))
    if (p.items?.[0]?.name) project.value = p.items[0].name
  } catch {
    stores.value = [{ name: '西溪天街店' }]
    therapists.value = [{ name: '郑世明', levelLabel: '资深' }]
  }
})
</script>

<template>
  <div class="mini-preview">
    <header class="dash-head">
      <div>
        <h1>小程序预览</h1>
        <p class="label">本机没有微信开发者工具。左边是当前实现，右边是设计稿。真机/开发者工具才能跑原生 WXML。</p>
      </div>
    </header>
    <div class="chip-row">
      <button
        v-for="s in SCREENS"
        :key="s.id"
        type="button"
        class="filter-chip"
        :class="{ on: screen === s.id }"
        @click="screen = s.id"
      >
        {{ s.title }}
      </button>
    </div>
    <div class="mini-stage">
      <div class="phone">
        <div class="phone-screen" :class="screen">
          <template v-if="screen === 'c1'">
            <div class="nav light">预约</div>
            <div class="scroll">
              <div class="hero-t">疲劳酸痛，到肌松大师</div>
              <div class="search">说说哪里不舒服，为你找师傅</div>
              <div class="card">
                <div class="sec-row"><b>特惠预约</b><span>更多活动</span></div>
                <div class="row-item">
                  <div class="thumb">技师照</div>
                  <div class="grow">
                    <b>{{ project }}</b>
                    <div class="price">¥188 <s>¥198</s></div>
                    <div class="meta">{{ therapists[0]?.name || '郑世明' }} · 今天可约</div>
                    <div class="tag">9.5折 · 仅此1档</div>
                  </div>
                  <button class="pill">立即抢约</button>
                </div>
              </div>
              <div class="card">
                <div class="sec-row"><b>门店预约</b><span>更多门店</span></div>
                <div v-for="(st, i) in (stores.length ? stores : [{ name: '西溪天街店' }])" :key="st.name" class="row-item">
                  <div class="thumb">门店</div>
                  <div class="grow">
                    <b>{{ st.name }}</b>
                    <div class="meta">10:00-22:00 <em v-if="i===0">就近</em></div>
                    <div class="jade">今晚可约</div>
                  </div>
                  <button class="pill ghost">预约</button>
                </div>
              </div>
              <div class="card">
                <div class="sec-row"><b>推拿师预约</b><span>按症找找</span></div>
                <div v-for="th in (therapists.length ? therapists : [{ name: '郑世明', levelLabel: '资深' }])" :key="th.name" class="row-item">
                  <div class="avatar"></div>
                  <div class="grow">
                    <b>{{ th.name }} <em>{{ th.levelLabel }}</em></b>
                    <div class="copper">4.9</div>
                  </div>
                  <button class="pill ghost">预约</button>
                </div>
              </div>
            </div>
            <div class="dock">
              <span class="on">预约</span><span>好礼</span><span>我的</span>
            </div>
          </template>

          <template v-else-if="screen === 'c2'">
            <div class="nav light">哪里不舒服</div>
            <div class="scroll">
              <div class="card">
                <b>按部位选</b>
                <div class="parts">
                  <div class="part on">头颈肩</div>
                  <div class="part">腰背</div>
                  <div class="part">下肢</div>
                </div>
                <b>按不适选</b>
                <div class="chips">
                  <span class="chip on">僵硬</span><span class="chip">酸痛</span><span class="chip">睡眠差</span>
                </div>
              </div>
              <div class="card">
                <div class="sec-row"><b>头颈肩 · 推荐项目</b></div>
                <div class="proj on">
                  <div class="sec-row"><b>{{ project }}</b><span class="price">¥198</span></div>
                  <div class="meta">60 分钟</div>
                  <div class="jade">今晚可约</div>
                  <button class="pill">选技师</button>
                </div>
              </div>
              <div class="note">选不准可先到店，由技师面诊后调整项目。</div>
            </div>
          </template>

          <template v-else-if="screen === 'c3'">
            <div class="nav light">选择时段</div>
            <div class="scroll">
              <div class="card row-item">
                <div class="avatar"></div>
                <div>
                  <b>{{ therapists[0]?.name || '郑世明' }} <em>资深</em></b>
                  <div class="copper">4.9 分</div>
                </div>
              </div>
              <div class="dates">
                <div class="date on">今天<br>15</div>
                <div class="date">周六<br>16</div>
                <div class="date">周日<br>17</div>
              </div>
              <div class="legend"><i class="free"></i>可约 <i class="locked"></i>锁定 <i class="booked"></i>已约 <i class="busy"></i>不可约</div>
              <div class="slots">
                <span class="slot free">10:00</span>
                <span class="slot booked">10:15</span>
                <span class="slot locked">11:00</span>
                <span class="slot busy">11:15</span>
                <span class="slot free">19:30</span>
                <span class="slot free">19:45</span>
              </div>
            </div>
            <div class="paybar">
              <div><small>点绿色起点预约</small><b class="price">¥198</b></div>
              <button class="pill">去预约</button>
            </div>
          </template>

          <template v-else-if="screen === 'c4'">
            <div class="nav light">确认支付</div>
            <div class="scroll">
              <div class="ticker">时段已锁定，请在 14:32 内完成支付</div>
              <div class="card">
                <b>{{ project }}</b>
                <div class="meta">{{ therapists[0]?.name || '郑世明' }} · 60 分钟</div>
                <div class="kv"><span>到店时间</span><b>今天 19:30</b></div>
                <div class="kv"><span>门店</span><b>{{ stores[0]?.name || '西溪天街店' }}</b></div>
                <div class="kv"><span>房间</span><span>到店由前台分配</span></div>
              </div>
              <div class="card"><b>支付方式</b><div class="pay">微信支付</div></div>
            </div>
            <div class="paybar">
              <div><b class="price">¥198</b><small>锁定 15 分钟</small></div>
              <button class="pill">立即支付</button>
            </div>
          </template>

          <template v-else-if="screen === 'c6'">
            <div class="nav light">我的</div>
            <div class="scroll">
              <div class="profile">
                <div class="row-item">
                  <div class="avatar light"></div>
                  <div>
                    <b>186****7752</b>
                    <div class="sub">调理 12 次 · 常约技师 {{ therapists[0]?.name || '郑世明' }}</div>
                  </div>
                </div>
                <div class="wallet">
                  <div>储值余额<b>¥0.00</b></div>
                  <div>礼卡余额<b>¥0.00</b></div>
                  <span class="recharge">充值</span>
                </div>
              </div>
              <div class="card">
                <div class="sec-row"><b>下一次调理</b><span>全部订单 ›</span></div>
                <div class="next-box">
                  <div class="sec-row"><b>{{ project }}</b><em class="st">待到店</em></div>
                  <div class="meta">今天 19:30 · {{ therapists[0]?.name || '郑世明' }}</div>
                  <div class="acts"><button class="pill">核销码</button><button class="pill ghost">改约</button><button class="pill ghost">取消</button></div>
                </div>
              </div>
              <div class="card menu">
                <div>项目订单</div><div>好礼订单</div><div>我的券包</div><div>理疗档案</div>
              </div>
            </div>
            <div class="dock"><span>预约</span><span>好礼</span><span class="on">我的</span></div>
          </template>

          <template v-else-if="screen === 't1'">
            <div class="hero-dark">
              <div class="row-item">
                <div class="avatar light"></div>
                <div class="grow">
                  <b>{{ therapists[0]?.name || '郑世明' }}</b>
                  <div class="sub">资深技师 · 本店</div>
                </div>
                <span class="duty">在岗</span>
              </div>
              <div class="kpis"><div>今日已完成<b>2 钟</b></div><div>预估收入<b>—</b></div><div>今日满班<b>18%</b></div></div>
            </div>
            <div class="scroll sand">
              <div class="next-card">
                <div class="sec-row"><b>下一单</b><span class="eta">23 分钟后</span></div>
                <div class="who">王女士 <em>熟客</em></div>
                <div>{{ project }}</div>
                <div class="when">19:30–20:30 <span class="room">3 号房</span></div>
                <div class="acts"><button class="pill wide">开始服务</button><button class="pill ghost">档案</button></div>
              </div>
              <div class="card">
                <b>今日时间轴</b>
                <div class="tl"><span>14:00</span><span>李先生 · 腰背</span><em>已完成</em></div>
                <div class="tl gap"><span>17:00</span><span>空档 90 分钟</span><button class="pill ghost sm">填满它</button></div>
                <div class="tl"><span>19:30</span><span>王女士 · 头颈肩</span><em>待服务</em></div>
              </div>
            </div>
            <div class="dock"><span class="on">工作台</span><span>排班</span><span>业绩</span><span>我的</span></div>
          </template>

          <template v-else-if="screen === 't2'">
            <div class="hero-dark center">
              <div class="sub">服务进行中 · 3 号房</div>
              <div class="timer">28:15</div>
              <div class="sub">王女士 · {{ project }}</div>
              <div class="acts"><button class="pill outline">申请加钟</button><button class="pill light">结束服务</button></div>
            </div>
            <div class="scroll sand">
              <div class="card">
                <div class="sec-row"><b>理疗记录</b><span>提交后不可删除</span></div>
                <div class="field">主诉</div>
                <div class="box">右侧颈肩僵硬，转头受限</div>
                <div class="field">手法</div>
                <div class="chips"><span class="chip on">滚法</span><span class="chip on">拿捏</span><span class="chip">点按</span></div>
                <div class="field">力度</div>
                <div class="chips three"><span class="chip">轻</span><span class="chip on">中</span><span class="chip">重</span></div>
                <div class="consent">☑ 已向客户口头告知本次记录内容</div>
              </div>
              <button class="pill wide bottom">提交记录并结单</button>
            </div>
          </template>

          <template v-else>
            <div class="hero-dark">
              <b>经营概览</b>
              <div class="pills"><span>本店</span><span>今日 · 实时</span></div>
              <div class="rate-row"><div>今日满班率<b class="big">0.0%</b></div><div>今日营收<b>—</b></div></div>
              <div class="track"><i></i></div>
              <div class="sub">剩余可售 slot · 19:00 后低谷</div>
            </div>
            <div class="scroll sand">
              <div class="card">
                <div class="sec-row"><b>待我审批</b><span class="badge">0</span></div>
                <div class="empty">队列已清空</div>
              </div>
              <div class="card">
                <b>门店实时</b>
                <div class="store-r"><span>{{ stores[0]?.name || '本店' }}</span><i></i><em>0%</em></div>
              </div>
            </div>
            <div class="dock"><span class="on">概览</span><span>审批</span><span>排班</span><span>我的</span></div>
          </template>
        </div>
      </div>
      <figure class="mock">
        <figcaption>设计稿 · {{ current.title }}</figcaption>
        <img :src="current.mock" :alt="current.title" />
      </figure>
    </div>
  </div>
</template>
