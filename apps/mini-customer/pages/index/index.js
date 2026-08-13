Page({
  data: {},
  goSymptom() {
    wx.navigateTo({ url: '/pages/symptom/symptom' })
  },
  goStores() {
    wx.navigateTo({ url: '/pages/stores/stores' })
  },
  goTherapists() {
    wx.navigateTo({ url: '/pages/therapists/therapists' })
  },
  goMine() {
    wx.redirectTo({ url: '/pages/mine/mine' })
  },
})
